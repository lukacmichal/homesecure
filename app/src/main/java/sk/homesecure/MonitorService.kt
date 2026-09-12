package sk.homesecure

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Srdce appky. Bezi 24/7 ako foreground service a riadi cely cyklus:
 *
 *   sken siete -> nikto doma -> kamera -> pohyb -> fotky do karanteny
 *      -> ochranne okno -> bud sa zahodia (prisiel si), alebo odosle mail
 *
 * Dve veci, ktore su tu zamerne tak, ako su:
 *
 *  - Kamera sa otvara az pri zapnuti strazenia. Kym si doma, appka kameru vobec
 *    nedrzi, takze telefon moze normalne sluzit ako prehravac hudby a fotoaparat
 *    sa neblokuje.
 *  - Sluzbu treba nastartovat z popredia (vid TrampolineActivity). Android 11+
 *    odopiera kameru sluzbam, ktore vznikli na pozadi, a to sa uz neda spatne
 *    napravit inak nez restartom sluzby.
 */
class MonitorService : LifecycleService() {

    companion object {
        const val ACTION_START = "sk.homesecure.action.START"
        const val ACTION_STOP = "sk.homesecure.action.STOP"
        const val ACTION_TEST = "sk.homesecure.action.TEST"

        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presence = PresenceMonitor()
    private lateinit var store: EventStore

    private var camera: CameraController? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var armed = false

    @Volatile
    private var capturing = false

    @Volatile
    private var cooldownUntil = 0L

    private var lastLoggedPresence: PresenceMonitor.Presence? = null
    private var roundCounter = 0

    private val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    // ---- zivotny cyklus -----------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        Notifications.ensureChannels(this)
        store = EventStore(this)
        isRunning = true

        // Zamerne neutralny text. Driv tu bolo "Doma - strazenie vypnute", takze
        // keby sa sken z akehokolvek dovodu nerozbehol, notifikacia sebavedome
        // tvrdila nieco, co appka vobec nezistila.
        goForeground(getString(R.string.state_starting))
        acquireWakeLock()
        scheduleWatchdog()

        EventLog.log("Služba spustená")
        startLoops()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                EventLog.log("Stráženie vypnuté")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> scope.launch { runTest() }
        }
        // START_STICKY: keby system sluzbu zabil, chceme ju spat. Kameru si po
        // takom restarte vypyta znova az watchdog cez trampolinu.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        isRunning = false
        EventLog.log("Služba ukončená")
        runCatching { runBlocking { camera?.stop() } }
        camera = null
        releaseWakeLock()
        Status.update { it.copy(mode = Status.Mode.OFF, cameraRunning = false) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- slucky -------------------------------------------------------------

    private fun startLoops() {
        scope.launch { presenceLoop() }
        scope.launch { quarantineLoop() }
        scope.launch { outboxLoop() }
        scope.launch { updateLoop() }
    }

    /** Sken siete a prepinanie medzi "doma" a "prec". */
    private suspend fun presenceLoop() {
        var wifiWarned = false
        while (scope.isActive) {
            val cfg = Prefs.detect(this)
            try {
                if (!Prefs.enabled(this)) {
                    if (armed) disarm(discardQuarantine = true)
                    Status.update { it.copy(mode = Status.Mode.OFF) }
                    updateNotification()
                    delay(5_000)
                    continue
                }

                if (!Probe.isOnWifi(this)) {
                    // Bez vlastnej Wi-Fi je sken bezcenny. Stav nechavame tak, ako
                    // bol - ked uz strazime, strazi sa dalej a maily sa medzitym
                    // kopia vo fronte.
                    if (!wifiWarned) {
                        EventLog.log("Telefón nie je na Wi-Fi, sken pozastavený")
                        wifiWarned = true
                    }
                    delay(cfg.pollIdleMs)
                    continue
                }
                if (wifiWarned) {
                    EventLog.log("Wi-Fi je späť, pokračujem v skenovaní")
                    wifiWarned = false
                }

                val devices = Prefs.devices(this)
                val round = presence.poll(devices, cfg.awayRounds, cfg.allowTcpKnock)
                logRound(round)
                when (round.presence) {
                    PresenceMonitor.Presence.PRESENT -> onPresent()
                    PresenceMonitor.Presence.ABSENT -> onAbsent(cfg)
                    PresenceMonitor.Presence.UNKNOWN -> onUnknown()
                }

                // Kamera sa mohla nepodarit otvorit (drzala ju ina appka) -
                // skusame to znova, kym strazime.
                if (armed && camera?.isRunning != true) startCamera(cfg)

                Status.update {
                    it.copy(
                        seenDevice = presence.lastSeenDevice,
                        lastProbeAt = System.currentTimeMillis(),
                        missedRounds = presence.missedRounds
                    )
                }
                updateNotification()
            } catch (t: Throwable) {
                EventLog.log("Chyba v sledovaní siete: ${t.message}")
            }

            // Ked strazime alebo drzime fotky v karantene, chceme navrat zachytit
            // co najskor - vtedy skenujeme casto.
            val fast = armed || store.listQuarantine().isNotEmpty()
            delay(if (fast) cfg.pollFastMs else cfg.pollIdleMs)
        }
    }

    /** Uvolnovanie fotiek z karanteny po uplynuti ochranneho okna. */
    private suspend fun quarantineLoop() {
        while (scope.isActive) {
            delay(1_000)
            try {
                val graceMs = Prefs.detect(this).graceMs
                val now = System.currentTimeMillis()
                for (event in store.listQuarantine()) {
                    if (now - event.capturedAt < graceMs) continue
                    val released = store.release(event)
                    if (released != null) {
                        val count = released.photos.size
                        EventLog.log("Ochranné okno uplynulo – $count fotiek ide na mail")
                        Notifications.alert(
                            this,
                            "Pohyb v byte",
                            "Zachytených $count fotiek o ${timeFmt.format(Date(released.capturedAt))}."
                        )
                    }
                }
                Status.update {
                    it.copy(
                        quarantined = store.listQuarantine().size,
                        outbox = store.listOutbox().size
                    )
                }
            } catch (t: Throwable) {
                EventLog.log("Chyba pri spracovaní karantény: ${t.message}")
            }
        }
    }

    /** Odosielanie fotiek mailom, s opakovanim pri vypadku internetu. */
    private suspend fun outboxLoop() {
        var backoffMs = 15_000L
        while (scope.isActive) {
            try {
                val pending = store.listOutbox()
                if (pending.isEmpty()) {
                    backoffMs = 15_000L
                    delay(5_000)
                    continue
                }

                val mail = Prefs.mail(this)
                if (!mail.isUsable) {
                    Status.update { it.copy(lastError = "Mail nie je nastavený") }
                    delay(60_000)
                    continue
                }

                var failed = false
                for (event in pending) {
                    if (!scope.isActive) break
                    val photos = event.photos
                    if (photos.isEmpty()) {
                        store.done(event)
                        continue
                    }
                    val at = timeFmt.format(Date(event.capturedAt))
                    try {
                        withContext(Dispatchers.IO) {
                            MailSender.send(
                                cfg = mail,
                                subject = "HomeSecure: pohyb $at",
                                body = buildString {
                                    appendLine("Zachytený pohyb, kým nikto z dôveryhodných zariadení nebol na Wi-Fi.")
                                    appendLine()
                                    appendLine("Čas: $at")
                                    appendLine("Počet fotiek: ${photos.size}")
                                },
                                attachments = photos
                            )
                        }
                        store.done(event)
                        // Do pocitadla dat. Mail s fotkami je najvacsi prenos,
                        // aky appka spravi, a ako jediny ide HORE — na
                        // mobilnych datach stoji rovnako ako stahovanie.
                        // Base64 v prilohe nafukne subor o tretinu, preto
                        // 4/3: pocitadlo ma byt horny odhad, nie prikraslenie.
                        Prenos.zapis(this@MonitorService,
                            photos.sumOf { it.length() } * 4 / 3)
                        EventLog.log("Mail odoslaný ($at, ${photos.size} fotiek)")
                        Status.update { it.copy(lastError = null) }
                        backoffMs = 15_000L
                    } catch (t: Throwable) {
                        failed = true
                        EventLog.log("Mail sa nepodarilo odoslať: ${t.message}")
                        Status.update { it.copy(lastError = t.message) }
                        break
                    }
                }

                store.prune()
                Status.update { it.copy(outbox = store.listOutbox().size) }

                if (failed) {
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, 10 * 60_000L)
                }
            } catch (t: Throwable) {
                EventLog.log("Chyba vo fronte mailov: ${t.message}")
                delay(30_000)
            }
        }
    }

    /** Obcasna kontrola novej verzie na NAS-e. */
    private suspend fun updateLoop() {
        // Po starte chvilu pockame, nech sa najprv rozbehne to podstatne.
        delay(2 * 60_000)
        while (scope.isActive) {
            try {
                val cfg = Prefs.update(this)
                val elapsed = System.currentTimeMillis() - Prefs.lastUpdateCheck(this)
                if (cfg.autoCheck && elapsed >= cfg.checkIntervalHours * 3_600_000L) {
                    Prefs.setLastUpdateCheck(this, System.currentTimeMillis())
                    when (val outcome = UpdateChecker.check(this, cfg)) {
                        is UpdateChecker.Outcome.Available -> {
                            EventLog.log("Na NAS-e je novšia verzia (${outcome.version})")
                            Notifications.alert(
                                this,
                                "Nová verzia HomeSecure",
                                "Na NAS-e je verzia ${outcome.version}. Otvor aplikáciu a ťukni na Inštalovať."
                            )
                        }
                        is UpdateChecker.Outcome.Failed ->
                            EventLog.log("Kontrola aktualizácie zlyhala: ${outcome.message}")
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                EventLog.log("Chyba pri kontrole aktualizácie: ${t.message}")
            }
            delay(30 * 60_000)
        }
    }

    /**
     * Zapisuje vysledok skenu do dennika - pri kazdej zmene a inak raz za cas.
     * Bez toho sa neda zvonku rozlisit "sken bezi a zariadenie odpoveda" od
     * "sken sa vobec netoci", co je pri ladeni to najpodstatnejsie.
     */
    private fun logRound(round: PresenceMonitor.Round) {
        roundCounter++
        val changed = round.presence != lastLoggedPresence
        if (!changed && roundCounter % 20 != 0) return
        lastLoggedPresence = round.presence

        val label = when (round.presence) {
            PresenceMonitor.Presence.PRESENT -> "doma"
            PresenceMonitor.Presence.ABSENT -> "preč"
            PresenceMonitor.Presence.UNKNOWN -> "neznáme (prázdny zoznam)"
        }
        // Metoda sa vypisuje zamerne: ked appka tvrdi nieco ine nez rucny ping,
        // je toto jediny udaj, ktory povie preco.
        val detail = round.details.joinToString(", ") { r ->
            if (r.up) "${r.device.name} odpovedá (${r.method})" else "${r.device.name} mlčí"
        }
        EventLog.log(
            "Sken: $label" +
                (if (detail.isNotEmpty()) " – $detail" else "") +
                ", zmeškaných kôl: ${presence.missedRounds}"
        )
    }

    // ---- prechody stavov ----------------------------------------------------

    private suspend fun onPresent() {
        if (armed) {
            // Toto je ten podstatny moment: prisiel si domov. Vsetko, co je este
            // v karantene, vzniklo v poslednych sekundach a takmer isto si to ty.
            disarm(discardQuarantine = true)
        } else {
            val stale = store.listQuarantine()
            if (stale.isNotEmpty()) {
                stale.forEach { store.discard(it) }
                EventLog.log("Doma – zahadzujem ${stale.size} fotiek z karantény")
            }
        }
        Status.update { it.copy(mode = Status.Mode.DISARMED) }
    }

    private suspend fun onAbsent(cfg: DetectConfig) {
        if (!armed) {
            armed = true
            EventLog.log("Nikto z dôveryhodných zariadení nie je na sieti – zapínam kameru")
            startCamera(cfg)
        }
        val mode =
            if (store.listQuarantine().isNotEmpty()) Status.Mode.GRACE else Status.Mode.ARMED
        Status.update { it.copy(mode = mode) }
    }

    /**
     * Bez zoznamu zariadeni sa neda rozhodnut, ci si doma. Nestrazime, ale musi
     * to byt na prvy pohlad vidiet - preto vlastny stav a nie "doma".
     */
    private suspend fun onUnknown() {
        if (armed) disarm(discardQuarantine = true)
        Status.update { it.copy(mode = Status.Mode.NO_DEVICES) }
    }

    private suspend fun disarm(discardQuarantine: Boolean) {
        armed = false
        camera?.stop()
        Status.update { it.copy(cameraRunning = false) }
        if (discardQuarantine) {
            val pending = store.listQuarantine()
            if (pending.isNotEmpty()) {
                val photos = pending.sumOf { it.photos.size }
                pending.forEach { store.discard(it) }
                EventLog.log("Si doma – $photos fotiek zahodených, mail sa neposiela")
            }
        }
        EventLog.log("Doma – stráženie vypnuté")
    }

    private suspend fun startCamera(cfg: DetectConfig) {
        if (!hasCameraPermission()) {
            Status.update { it.copy(lastError = "Chýba povolenie na kameru") }
            return
        }
        val controller = camera ?: CameraController(this, this).also { new ->
            new.onMotion = { onMotionDetected() }
            camera = new
        }
        val result = controller.start(cfg)
        if (result.isSuccess) {
            // Podrobnosti (rozlisenie, kvalita) zapisuje uz CameraController.
            Status.update { it.copy(cameraRunning = true, lastError = null) }
        } else {
            val msg = result.exceptionOrNull()?.message ?: "neznáma chyba"
            EventLog.log("Kameru sa nepodarilo otvoriť: $msg")
            Status.update { it.copy(cameraRunning = false, lastError = msg) }
        }
    }

    // ---- fotenie ------------------------------------------------------------

    /** Vola sa z analyzacneho vlakna kamery. Musi byt rychle a nesmie blokovat. */
    private fun onMotionDetected() {
        if (!armed || capturing) return
        if (SystemClock.elapsedRealtime() < cooldownUntil) return
        capturing = true
        camera?.motionEnabled = false
        scope.launch { captureBurst() }
    }

    private suspend fun captureBurst() {
        val cfg = Prefs.detect(this)
        val controller = camera
        try {
            if (controller == null) return
            val event = store.createQuarantine()
            val taken = mutableListOf<File>()
            for (i in 0 until cfg.burstCount) {
                val target = File(event.dir, "foto_${i + 1}.jpg")
                controller.capture(target)?.let { taken.add(it) }
                if (i < cfg.burstCount - 1) delay(cfg.burstSpacingMs)
            }

            if (taken.isEmpty()) {
                store.discard(event)
                EventLog.log("Pohyb zachytený, ale fotenie zlyhalo")
            } else {
                val seconds = cfg.graceMs / 1000
                val kb = taken.sumOf { it.length() } / 1024 / taken.size
                EventLog.log(
                    "Pohyb – ${taken.size} fotiek (~$kb kB/ks) v karanténe, " +
                        "ochranné okno $seconds s"
                )
                Status.update {
                    it.copy(mode = Status.Mode.GRACE, quarantined = store.listQuarantine().size)
                }
                updateNotification()
            }
        } catch (t: Throwable) {
            EventLog.log("Chyba pri fotení: ${t.message}")
        } finally {
            cooldownUntil = SystemClock.elapsedRealtime() + cfg.cooldownMs
            capturing = false
            camera?.resetDetector()
            camera?.motionEnabled = true
        }
    }

    /** Testovacie odfotenie a odoslanie - obchadza ochranne okno. */
    private suspend fun runTest() {
        EventLog.log("Test: fotím a posielam mail")
        val cfg = Prefs.detect(this)
        val hadCamera = camera?.isRunning == true
        if (!hadCamera) startCamera(cfg)

        val controller = camera
        if (controller == null || !controller.isRunning) {
            EventLog.log("Test zlyhal: kamera sa nedá otvoriť")
            return
        }

        val event = store.createQuarantine()
        val target = File(event.dir, "test.jpg")
        val file = controller.capture(target)
        if (file == null) {
            store.discard(event)
            EventLog.log("Test zlyhal: fotenie neprešlo")
        } else {
            // Test posielame rovno, nie cez ochranne okno.
            store.release(event)
            EventLog.log("Test: fotka je vo fronte na odoslanie")
        }
        if (!hadCamera && !armed) controller.stop()
    }

    // ---- drobnosti ----------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun goForeground(text: String) {
        val notification = Notifications.monitor(this, text)
        // Od Androidu 14 musi typ sedet s tym, co je v manifeste, a kameru smie
        // deklarovat len sluzba, ktora na nu naozaj ma povolenie.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasCameraPermission()
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_MONITOR, notification, type)
        }.onFailure {
            EventLog.log("Nepodarilo sa spustiť foreground službu: ${it.message}")
        }
    }

    private fun updateNotification() {
        val s = Status.state.value
        val text = when (s.mode) {
            Status.Mode.OFF -> getString(R.string.state_paused)
            Status.Mode.NO_DEVICES -> getString(R.string.state_no_devices)
            Status.Mode.DISARMED ->
                getString(R.string.state_disarmed) +
                    (s.seenDevice?.let { " ($it)" } ?: "")
            Status.Mode.ARMED -> getString(R.string.state_armed)
            Status.Mode.GRACE -> getString(R.string.state_grace)
        }
        Notifications.updateMonitor(this, text)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        // Telefon ma visiet na nabijacke - drzime CPU hore, aby analyza snimkov
        // a skenovanie siete bezali aj so zhasnutym displejom.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeSecure::monitor").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * Keby system sluzbu aj tak zabil, budik ju o par minut oziv. Je to posledna
     * poistka - START_STICKY sam o sebe na vsetkych vyrobcoch nestaci.
     */
    private fun scheduleWatchdog() {
        val am = getSystemService(ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, WatchdogReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            pi
        )
    }
}
