package sk.homesecure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hlavna obrazovka: stav strazenia, hlavny prepinac, testovacie tlacidla
 * a dennik.
 *
 * Zoznam "este treba dopovolit" je tu zamerne az agresivne viditelny - kazde
 * z tych povoleni je nutne na to, aby appka prezila reboot alebo vobec otvorila
 * kameru, a bez upozornenia by sa na ne prislo az vtedy, ked uz je neskoro.
 */
class MainActivity : ZakladActivity() {

    private lateinit var tvMode: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLog: TextView
    private lateinit var verFooter: TextView
    private lateinit var tvWarnings: TextView
    private lateinit var cardWarnings: LinearLayout
    private lateinit var warnButtons: LinearLayout
    private lateinit var swEnabled: SwitchCompat

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshWarnings()
            if (Prefs.enabled(this)) ServiceLauncher.startDirect(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EventLog.init(this)
        Notifications.ensureChannels(this)

        tvMode = findViewById(R.id.tvMode)
        tvDetail = findViewById(R.id.tvDetail)
        tvLog = findViewById(R.id.tvLog)
        verFooter = findViewById(R.id.verFooter)
        tvWarnings = findViewById(R.id.tvWarnings)
        cardWarnings = findViewById(R.id.cardWarnings)
        warnButtons = findViewById(R.id.warnButtons)
        swEnabled = findViewById(R.id.swEnabled)

        swEnabled.isChecked = Prefs.enabled(this)
        swEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            if (checked) {
                askForMissingPermissions()
                ServiceLauncher.startDirect(this)
            } else {
                ServiceLauncher.stop(this)
            }
            refreshWarnings()
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Potiahnutie dole aj ikona ↻ prekontroluju povolenia a stav sluzby —
        // rovnake gesto ako v ostatnych appkach (docs/shared-standard.md 2.6).
        val potiahni = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.potiahni,
        )
        potiahni.setOnRefreshListener {
            refreshWarnings()
            potiahni.isRefreshing = false
        }
        findViewById<ImageButton>(R.id.obnov).setOnClickListener { refreshWarnings() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { runCaptureTest() }
        findViewById<Button>(R.id.btnPing).setOnClickListener { runPingTest() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { runUpdateCheck() }
        findViewById<TextView>(R.id.btnClearLog).setOnClickListener { EventLog.clear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Status.state.collect { render(it) } }
                launch {
                    EventLog.lines.collect { lines ->
                        tvLog.text = lines.takeLast(60).reversed().joinToString("\n")
                    }
                }
            }
        }

        VersionUi.render(verFooter, remote = null)
        // Tuknutie = rucna kontrola, aj ked je automaticka v Nastaveniach vypnuta:
        // klik JE ta ziadost.
        verFooter.setOnClickListener { checkUpdate(manual = true) }
        checkUpdate(manual = false)
    }

    /**
     * Kontrola aktualizacie - len par stoviek bajtov metadat, ziadne APK.
     * Sluzba si svoju kontrolu robi kazdych 6 hodin, ale bezi len ked je
     * strazenie zapnute; toto pokryva aj vypnutu appku.
     *
     * Instaluje sa az po potvrdeni a pri nedostupnom NAS-e sa nedeje nic.
     */
    private fun checkUpdate(manual: Boolean) {
        val cfg = Prefs.update(this)
        if (!manual && !cfg.autoCheck) return
        lifecycleScope.launch {
            val remote = UpdateChecker.peekRemote(cfg)
            if (isFinishing || isDestroyed) return@launch
            if (remote == null) {
                if (manual) toast("NAS je nedostupný.")
                return@launch
            }
            val current = ApkInstaller.currentVersionCode(this@MainActivity)
            val novsia = remote.code > current
            VersionUi.render(verFooter, remote.toString(), novsia)
            if (!novsia) {
                if (manual) toast("Máš najnovšiu verziu.")
                return@launch
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.up_prompt_title)
                .setMessage(
                    getString(
                        R.string.up_prompt_msg,
                        remote.toString(),
                        VersionUi.installed(this@MainActivity),
                    )
                )
                .setPositiveButton(R.string.up_prompt_yes) { _, _ -> runUpdateCheck() }
                .setNegativeButton(R.string.up_prompt_later, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        swEnabled.isChecked = Prefs.enabled(this)
        refreshWarnings()
    }

    // ---- vykreslovanie ------------------------------------------------------

    private fun render(s: Status.Snapshot) {
        val (label, color) = when (s.mode) {
            Status.Mode.OFF -> getString(R.string.state_paused) to R.color.text_dim
            Status.Mode.NO_DEVICES -> getString(R.string.state_no_devices) to R.color.alarm
            Status.Mode.DISARMED -> getString(R.string.state_disarmed) to R.color.ok
            Status.Mode.ARMED -> getString(R.string.state_armed) to R.color.warn
            Status.Mode.GRACE -> getString(R.string.state_grace) to R.color.alarm
        }
        tvMode.text = label
        tvMode.setTextColor(ContextCompat.getColor(this, color))

        val watched = Prefs.devices(this)
        val detail = buildString {
            // Vypisujeme, co parser naozaj precital - bez toho sa zle hlada
            // chyba vo formate zoznamu.
            if (watched.isEmpty()) {
                appendLine("Zadaj ich v nastaveniach vo formáte Meno=IP")
                appendLine("(alebo len samotnú IP adresu).")
            } else {
                appendLine("Sledujem: " + watched.joinToString(", ") { "${it.name} – ${it.ip}" })
            }
            if (s.seenDevice != null && s.mode == Status.Mode.DISARMED) {
                appendLine("Na sieti: ${s.seenDevice}")
            }
            if (s.mode == Status.Mode.ARMED || s.mode == Status.Mode.GRACE) {
                appendLine("Kamera: ${if (s.cameraRunning) "beží" else "nebeží"}")
            }
            if (s.missedRounds > 0) appendLine("Neúspešných kôl po sebe: ${s.missedRounds}")
            if (s.quarantined > 0) appendLine("V karanténe: ${s.quarantined}")
            if (s.outbox > 0) appendLine("Čaká na odoslanie: ${s.outbox}")
            if (s.lastProbeAt > 0) appendLine("Posledný sken: ${timeFmt.format(Date(s.lastProbeAt))}")
            s.lastError?.let { appendLine("Chyba: $it") }
        }.trim()
        tvDetail.text = detail
        tvDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- povolenia ----------------------------------------------------------

    private fun refreshWarnings() {
        val problems = mutableListOf<String>()
        warnButtons.removeAllViews()

        if (!hasPermission(Manifest.permission.CAMERA)) {
            problems += "• Kamera – bez nej sa nedá fotiť."
            addFixButton("Povoliť kameru") { askForMissingPermissions() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            problems += "• Notifikácie – bez nich nemôže bežať služba na pozadí."
            addFixButton("Povoliť notifikácie") { askForMissingPermissions() }
        }
        if (!ServiceLauncher.canDrawOverlays(this)) {
            problems += "• Zobrazovanie cez iné aplikácie – bez toho sa po reštarte " +
                "telefónu nerozbehne kamera sama."
            addFixButton("Povoliť zobrazovanie cez iné appky") {
                ServiceLauncher.openOverlaySettings(this)
            }
        }
        if (!isIgnoringBatteryOptimizations()) {
            problems += "• Vypnutá optimalizácia batérie – inak systém službu skôr či " +
                "neskôr uspí."
            addFixButton("Vypnúť optimalizáciu batérie") { requestIgnoreBattery() }
        }
        if (!ApkInstaller.canInstall(this)) {
            problems += "• Inštalácia neznámych aplikácií – potrebné na aktualizácie z NAS-u."
            addFixButton("Povoliť inštaláciu z NAS-u") {
                ApkInstaller.openInstallPermissionSettings(this)
            }
        }
        if (Prefs.devices(this).isEmpty()) {
            problems += "• Nie sú zadané dôveryhodné zariadenia – bez nich sa stráženie " +
                "nikdy nezapne."
        }
        if (!Prefs.mail(this).isUsable) {
            problems += "• Nie je nastavený mail – fotky sa nemajú kam poslať."
        }

        if (problems.isEmpty()) {
            cardWarnings.visibility = View.GONE
        } else {
            cardWarnings.visibility = View.VISIBLE
            tvWarnings.text = problems.joinToString("\n")
        }
    }

    private fun addFixButton(text: String, action: () -> Unit) {
        val b = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setBackgroundResource(R.drawable.btn_ghost_bg)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (46 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { action() }
        }
        warnButtons.addView(b)
    }

    private fun askForMissingPermissions() {
        val wanted = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) wanted += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }

    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBattery() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    // ---- testy --------------------------------------------------------------

    private fun runCaptureTest() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            askForMissingPermissions()
            return
        }
        if (!Prefs.mail(this).isUsable) {
            toast("Najprv nastav mail.")
            return
        }
        if (!MonitorService.isRunning) ServiceLauncher.startDirect(this)
        val intent = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TEST)
        ContextCompat.startForegroundService(this, intent)
        toast("Fotím a posielam – sleduj denník.")
    }

    private fun runPingTest() {
        val devices = Prefs.devices(this)
        if (devices.isEmpty()) {
            toast("Najprv zadaj dôveryhodné zariadenia v nastaveniach.")
            return
        }
        toast("Skenujem…")
        val allowKnock = Prefs.detect(this).allowTcpKnock
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                devices.map { d -> d to Probe.probe(d.ip, 2000, allowKnock) }
            }
            val text = results.joinToString(", ") { (d, hit) ->
                if (hit.up) "${d.name} je na sieti (${hit.method})"
                else "${d.name} neodpovedá"
            }
            EventLog.log("Test skenu: $text")
        }
    }

    private fun runUpdateCheck() {
        val cfg = Prefs.update(this)
        if (cfg.host.isBlank() || cfg.username.isBlank()) {
            toast("Najprv vyplň prístup na NAS v nastaveniach.")
            return
        }
        toast("Pozerám na NAS…")
        lifecycleScope.launch {
            Prefs.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            when (val outcome = UpdateChecker.check(this@MainActivity, cfg)) {
                is UpdateChecker.Outcome.Available -> {
                    EventLog.log("Nájdená verzia ${outcome.version}, spúšťam inštaláciu")
                    if (ApkInstaller.canInstall(this@MainActivity)) {
                        ApkInstaller.install(this@MainActivity, outcome.apk)
                    } else {
                        toast("Povoľ inštaláciu neznámych aplikácií.")
                        ApkInstaller.openInstallPermissionSettings(this@MainActivity)
                    }
                }
                UpdateChecker.Outcome.UpToDate -> {
                    EventLog.log("Aktualizácia: máš najnovšiu verziu")
                    toast("Máš najnovšiu verziu.")
                }
                UpdateChecker.Outcome.NotConfigured -> toast("NAS nie je nastavený.")
                is UpdateChecker.Outcome.Failed -> {
                    EventLog.log("Aktualizácia zlyhala: ${outcome.message}")
                    toast("Nepodarilo sa: ${outcome.message}")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
