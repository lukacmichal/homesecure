package sk.homesecure

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat

/**
 * Formular so vsetkym, co sa da doladit. Casy zadava uzivatel v sekundach,
 * vnutri sa s nimi pracuje v milisekundach.
 */
class SettingsActivity : ZakladActivity() {

    private lateinit var etDevices: EditText
    private lateinit var etGrace: EditText
    private lateinit var etAwayRounds: EditText
    private lateinit var etPollIdle: EditText
    private lateinit var etPollFast: EditText
    private lateinit var etSensitivity: EditText
    private lateinit var etPhotoWidth: EditText
    private lateinit var etJpegQuality: EditText
    private lateinit var etBurst: EditText
    private lateinit var etBurstGap: EditText
    private lateinit var etCooldown: EditText
    private lateinit var swFrontCam: SwitchCompat
    private lateinit var swFlash: SwitchCompat
    private lateinit var swTcpKnock: SwitchCompat

    private lateinit var etMailTo: EditText
    private lateinit var etSmtpUser: EditText
    private lateinit var etSmtpPass: EditText
    private lateinit var etSmtpHost: EditText
    private lateinit var etSmtpPort: EditText

    private lateinit var etNasHost: EditText
    private lateinit var etNasShare: EditText
    private lateinit var etNasPath: EditText
    private lateinit var etNasFile: EditText
    private lateinit var etNasUser: EditText
    private lateinit var etNasPass: EditText
    private lateinit var swAutoUpdate: SwitchCompat
    private lateinit var etUpdateHours: EditText

    private lateinit var rgRezim: RadioGroup
    private lateinit var etPismo: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        load()
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        prenos()
    }

    /**
     * Prenesene data — Wi-Fi zvlast, mobilne zvlast.
     *
     * Nadpis, text aj tlacidlo pyta [Prenos]: sekcia je vo vsetkych appkach
     * rovnaka. Tu ma zmysel hlavne kvoli mailom s fotkami — to je jediny
     * prenos appky, ktory ide HORE a na mobilnych datach stoji rovnako.
     */
    private fun prenos() {
        findViewById<TextView>(R.id.tvPrenosNadpis).text = Prenos.NADPIS
        val hodnota = findViewById<TextView>(R.id.tvPrenos)
        hodnota.text = Prenos.popis(this)
        findViewById<TextView>(R.id.tvPrenosPoznamka).text = Prenos.POZNAMKA
        findViewById<Button>(R.id.btnPrenosReset).apply {
            text = Prenos.VYNULOVAT
            setOnClickListener {
                Prenos.vynuluj(this@SettingsActivity)
                hodnota.text = Prenos.popis(this@SettingsActivity)
            }
        }
    }

    private fun bindViews() {
        etDevices = findViewById(R.id.etDevices)
        etGrace = findViewById(R.id.etGrace)
        etAwayRounds = findViewById(R.id.etAwayRounds)
        etPollIdle = findViewById(R.id.etPollIdle)
        etPollFast = findViewById(R.id.etPollFast)
        etSensitivity = findViewById(R.id.etSensitivity)
        etPhotoWidth = findViewById(R.id.etPhotoWidth)
        etJpegQuality = findViewById(R.id.etJpegQuality)
        etBurst = findViewById(R.id.etBurst)
        etBurstGap = findViewById(R.id.etBurstGap)
        etCooldown = findViewById(R.id.etCooldown)
        swFrontCam = findViewById(R.id.swFrontCam)
        swFlash = findViewById(R.id.swFlash)
        swTcpKnock = findViewById(R.id.swTcpKnock)

        etMailTo = findViewById(R.id.etMailTo)
        etSmtpUser = findViewById(R.id.etSmtpUser)
        etSmtpPass = findViewById(R.id.etSmtpPass)
        etSmtpHost = findViewById(R.id.etSmtpHost)
        etSmtpPort = findViewById(R.id.etSmtpPort)

        rgRezim = findViewById(R.id.rgRezim)
        etPismo = findViewById(R.id.etPismo)

        etNasHost = findViewById(R.id.etNasHost)
        etNasShare = findViewById(R.id.etNasShare)
        etNasPath = findViewById(R.id.etNasPath)
        etNasFile = findViewById(R.id.etNasFile)
        etNasUser = findViewById(R.id.etNasUser)
        etNasPass = findViewById(R.id.etNasPass)
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
        etUpdateHours = findViewById(R.id.etUpdateHours)
    }

    private fun load() {
        rgRezim.check(
            when (Vzhlad.rezim(this)) {
                Vzhlad.SVETLY -> R.id.rbRezimZakladny
                Vzhlad.CIERNY -> R.id.rbRezimCierny
                else -> R.id.rbRezimSystem
            }
        )
        etPismo.setText(Vzhlad.pismo(this).toString())

        etDevices.setText(Prefs.devicesRaw(this))

        val d = Prefs.detect(this)
        etGrace.setText((d.graceMs / 1000).toString())
        etAwayRounds.setText(d.awayRounds.toString())
        etPollIdle.setText((d.pollIdleMs / 1000).toString())
        etPollFast.setText((d.pollFastMs / 1000).toString())
        etSensitivity.setText(d.sensitivity.toString())
        etPhotoWidth.setText(d.photoWidth.toString())
        etJpegQuality.setText(d.jpegQuality.toString())
        etBurst.setText(d.burstCount.toString())
        etBurstGap.setText((d.burstSpacingMs / 1000).toString())
        etCooldown.setText((d.cooldownMs / 1000).toString())
        swFrontCam.isChecked = d.useFrontCamera
        swFlash.isChecked = d.useFlash
        swTcpKnock.isChecked = d.allowTcpKnock

        val m = Prefs.mail(this)
        etMailTo.setText(m.to)
        etSmtpUser.setText(m.username)
        etSmtpPass.setText(m.password)
        etSmtpHost.setText(m.smtpHost)
        etSmtpPort.setText(m.smtpPort.toString())

        val u = Prefs.update(this)
        etNasHost.setText(u.host)
        etNasShare.setText(u.shareName)
        etNasPath.setText(u.remotePath)
        etNasFile.setText(u.apkFileName)
        etNasUser.setText(u.username)
        etNasPass.setText(u.password)
        swAutoUpdate.isChecked = u.autoCheck
        etUpdateHours.setText(u.checkIntervalHours.toString())
    }

    private fun save() {
        val podpisPred = Vzhlad.podpis(this)
        Vzhlad.uloz(
            this,
            when (rgRezim.checkedRadioButtonId) {
                R.id.rbRezimZakladny -> Vzhlad.SVETLY
                R.id.rbRezimCierny -> Vzhlad.CIERNY
                else -> Vzhlad.PODLA_SYSTEMU
            },
            etPismo.text.toString().trim().toIntOrNull() ?: 0,
        )
        // Nocny rezim si prekreslenie zariadi sam (AppCompatDelegate), velkost
        // pisma nie — layout uz je nafukany starym kontextom.
        if (Vzhlad.podpis(this) != podpisPred) recreate()

        Prefs.setDevicesRaw(this, etDevices.text.toString())

        val defaults = DetectConfig()
        Prefs.saveDetect(
            this,
            DetectConfig(
                // Spodne hranice su tam preto, aby sa preklepom nedala appka
                // dostat do stavu, kde pinguje kazdych 0 sekund.
                pollIdleMs = seconds(etPollIdle, defaults.pollIdleMs, min = 5),
                pollFastMs = seconds(etPollFast, defaults.pollFastMs, min = 2),
                awayRounds = int(etAwayRounds, defaults.awayRounds, min = 1, max = 60),
                graceMs = seconds(etGrace, defaults.graceMs, min = 5),
                burstCount = int(etBurst, defaults.burstCount, min = 1, max = 10),
                burstSpacingMs = seconds(etBurstGap, defaults.burstSpacingMs, min = 1),
                cooldownMs = seconds(etCooldown, defaults.cooldownMs, min = 1),
                sensitivity = int(etSensitivity, defaults.sensitivity, min = 1, max = 100),
                // 0 znamena "maximum", preto min = 0 a nie nejaka rozumna sirka.
                photoWidth = int(etPhotoWidth, defaults.photoWidth, min = 0, max = 8000),
                jpegQuality = int(etJpegQuality, defaults.jpegQuality, min = 60, max = 100),
                useFrontCamera = swFrontCam.isChecked,
                useFlash = swFlash.isChecked,
                allowTcpKnock = swTcpKnock.isChecked
            )
        )

        Prefs.saveMail(
            this,
            MailConfig(
                smtpHost = etSmtpHost.text.toString().trim(),
                smtpPort = int(etSmtpPort, 587, min = 1, max = 65535),
                username = etSmtpUser.text.toString().trim(),
                password = etSmtpPass.text.toString(),
                to = etMailTo.text.toString().trim()
            )
        )

        Prefs.saveUpdate(
            this,
            UpdateConfig(
                host = etNasHost.text.toString().trim(),
                shareName = etNasShare.text.toString().trim(),
                remotePath = etNasPath.text.toString().trim(),
                apkFileName = etNasFile.text.toString().trim(),
                username = etNasUser.text.toString().trim(),
                password = etNasPass.text.toString(),
                autoCheck = swAutoUpdate.isChecked,
                checkIntervalHours = int(etUpdateHours, 6, min = 1, max = 168)
            )
        )

        EventLog.log("Nastavenia uložené")
        Toast.makeText(this, "Uložené", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun int(field: EditText, fallback: Int, min: Int, max: Int): Int =
        field.text.toString().trim().toIntOrNull()?.coerceIn(min, max) ?: fallback

    private fun seconds(field: EditText, fallbackMs: Long, min: Long): Long {
        val value = field.text.toString().trim().toLongOrNull() ?: return fallbackMs
        return maxOf(min, value) * 1000
    }
}
