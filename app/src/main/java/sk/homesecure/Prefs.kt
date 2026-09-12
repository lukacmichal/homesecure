package sk.homesecure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Konfiguracia appky.
 *
 * Bezne nastavenia idu do obycajnych prefs, hesla (Gmail app password, SMB) do
 * sifrovanych. Ak by sa keystore na starom telefone rozbil, padneme spat na
 * obycajne prefs - appka ma fungovat aj vtedy. Zaloha na Google je vypnuta
 * (allowBackup="false"), takze hesla telefon neopustia.
 */
object Prefs {
    private const val TAG = "Prefs"
    private const val FILE = "homesecure"
    private const val FILE_SECRET = "homesecure_secret"

    private const val K_DEVICES = "devices"
    private const val K_ENABLED = "enabled"

    private const val K_POLL_IDLE = "poll_idle"
    private const val K_POLL_FAST = "poll_fast"
    private const val K_AWAY_ROUNDS = "away_rounds"
    private const val K_GRACE = "grace_ms"
    private const val K_BURST = "burst_count"
    private const val K_BURST_GAP = "burst_gap"
    private const val K_COOLDOWN = "cooldown_ms"
    private const val K_SENSITIVITY = "sensitivity"
    private const val K_FRONT_CAM = "front_cam"
    private const val K_TCP_KNOCK = "tcp_knock"
    private const val K_FLASH = "use_flash"
    private const val K_PHOTO_WIDTH = "photo_width"
    private const val K_JPEG_QUALITY = "jpeg_quality"

    private const val K_SMTP_HOST = "smtp_host"
    private const val K_SMTP_PORT = "smtp_port"
    private const val K_SMTP_USER = "smtp_user"
    private const val K_SMTP_PASS = "smtp_pass"
    private const val K_MAIL_TO = "mail_to"

    private const val K_UP_HOST = "up_host"
    private const val K_UP_SHARE = "up_share"
    private const val K_UP_PATH = "up_path"
    private const val K_UP_FILE = "up_file"
    private const val K_UP_USER = "up_user"
    private const val K_UP_PASS = "up_pass"
    private const val K_UP_DOMAIN = "up_domain"
    private const val K_UP_AUTO = "up_auto"
    private const val K_UP_HOURS = "up_hours"
    private const val K_UP_LAST = "up_last_check"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var secretCache: SharedPreferences? = null

    private fun secret(ctx: Context): SharedPreferences {
        secretCache?.let { return it }
        val app = ctx.applicationContext
        val prefs = try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                FILE_SECRET,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Keystore sa da na starsich/rootnutych zariadeniach rozbit. Radsej
            // ulozime heslo nesifrovane do app-private prefs, nez aby prestali
            // chodit maily.
            Log.w(TAG, "Sifrovane prefs nedostupne, pouzivam obycajne", t)
            app.getSharedPreferences("${FILE_SECRET}_plain", Context.MODE_PRIVATE)
        }
        secretCache = prefs
        return prefs
    }

    // ---- zapnutie strazenia -------------------------------------------------

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_ENABLED, value).apply()
    }

    // ---- dovervhodne zariadenia --------------------------------------------

    fun devicesRaw(ctx: Context): String = sp(ctx).getString(K_DEVICES, "").orEmpty()

    fun setDevicesRaw(ctx: Context, raw: String) {
        sp(ctx).edit().putString(K_DEVICES, raw.trim()).apply()
    }

    /**
     * Parsuje riadky "Meno=IP", "Meno=IP=MAC" alebo holu IP adresu.
     *
     * Holu IP beriem zamerne: je to najprirodzenejsie, co clovek do takeho pola
     * napise, a driv sa taky riadok ticho zahodil - cim ostal zoznam prazdny
     * a strazenie sa nikdy nezaplo.
     */
    fun devices(ctx: Context): List<TrustedDevice> =
        devicesRaw(ctx).lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null

            if (!t.contains("=")) {
                return@mapNotNull TrustedDevice(name = t, ip = t)
            }

            val parts = t.split("=").map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            val ip = parts.getOrNull(1).orEmpty()
            val mac = parts.getOrNull(2).orEmpty()
            if (ip.isEmpty()) null
            else TrustedDevice(name.ifEmpty { ip }, ip, mac)
        }

    // ---- detekcia -----------------------------------------------------------

    private val defDetect = DetectConfig()

    fun detect(ctx: Context): DetectConfig {
        val p = sp(ctx)
        return DetectConfig(
            pollIdleMs = p.getLong(K_POLL_IDLE, defDetect.pollIdleMs),
            pollFastMs = p.getLong(K_POLL_FAST, defDetect.pollFastMs),
            awayRounds = p.getInt(K_AWAY_ROUNDS, defDetect.awayRounds),
            graceMs = p.getLong(K_GRACE, defDetect.graceMs),
            burstCount = p.getInt(K_BURST, defDetect.burstCount),
            burstSpacingMs = p.getLong(K_BURST_GAP, defDetect.burstSpacingMs),
            cooldownMs = p.getLong(K_COOLDOWN, defDetect.cooldownMs),
            sensitivity = p.getInt(K_SENSITIVITY, defDetect.sensitivity),
            useFrontCamera = p.getBoolean(K_FRONT_CAM, defDetect.useFrontCamera),
            allowTcpKnock = p.getBoolean(K_TCP_KNOCK, defDetect.allowTcpKnock),
            useFlash = p.getBoolean(K_FLASH, defDetect.useFlash),
            photoWidth = p.getInt(K_PHOTO_WIDTH, defDetect.photoWidth),
            jpegQuality = p.getInt(K_JPEG_QUALITY, defDetect.jpegQuality)
        )
    }

    fun saveDetect(ctx: Context, c: DetectConfig) {
        sp(ctx).edit()
            .putLong(K_POLL_IDLE, c.pollIdleMs)
            .putLong(K_POLL_FAST, c.pollFastMs)
            .putInt(K_AWAY_ROUNDS, c.awayRounds)
            .putLong(K_GRACE, c.graceMs)
            .putInt(K_BURST, c.burstCount)
            .putLong(K_BURST_GAP, c.burstSpacingMs)
            .putLong(K_COOLDOWN, c.cooldownMs)
            .putInt(K_SENSITIVITY, c.sensitivity)
            .putBoolean(K_FRONT_CAM, c.useFrontCamera)
            .putBoolean(K_TCP_KNOCK, c.allowTcpKnock)
            .putBoolean(K_FLASH, c.useFlash)
            .putInt(K_PHOTO_WIDTH, c.photoWidth)
            .putInt(K_JPEG_QUALITY, c.jpegQuality)
            .apply()
    }

    // ---- mail ---------------------------------------------------------------

    private val defMail = MailConfig()

    /**
     * Prazdne ulozene pole znamena "nechaj predvolbu z buildu", nie "posielaj
     * bez prihlasenia" — inak by vymazanie pola umlcalo upozornenia natrvalo.
     */
    fun mail(ctx: Context): MailConfig {
        val p = sp(ctx)
        fun s(key: String, fallback: String) =
            p.getString(key, fallback).orEmpty().trim().ifEmpty { fallback }
        return MailConfig(
            smtpHost = s(K_SMTP_HOST, defMail.smtpHost),
            smtpPort = p.getInt(K_SMTP_PORT, defMail.smtpPort),
            username = s(K_SMTP_USER, defMail.username),
            password = secret(ctx).getString(K_SMTP_PASS, "").orEmpty()
                .ifEmpty { defMail.password },
            to = s(K_MAIL_TO, defMail.to)
        )
    }

    fun saveMail(ctx: Context, c: MailConfig) {
        sp(ctx).edit()
            .putString(K_SMTP_HOST, c.smtpHost.trim())
            .putInt(K_SMTP_PORT, c.smtpPort)
            .putString(K_SMTP_USER, c.username.trim())
            .putString(K_MAIL_TO, c.to.trim())
            .apply()
        // To iste ako pri hesle k NAS-u: app password zhodny s tym z buildu
        // neuklada, nech sa da zmenit na jednom mieste.
        secret(ctx).edit().apply {
            if (c.password.isEmpty() || c.password == BuildConfig.MAIL_PASS) {
                remove(K_SMTP_PASS)
            } else {
                putString(K_SMTP_PASS, c.password)
            }
        }.apply()
    }

    // ---- self-update --------------------------------------------------------

    private val defUp = UpdateConfig()

    fun update(ctx: Context): UpdateConfig {
        val p = sp(ctx)
        fun s(key: String, def: String) =
            p.getString(key, def).orEmpty().trim().ifEmpty { def }
        return UpdateConfig(
            host = s(K_UP_HOST, defUp.host),
            shareName = s(K_UP_SHARE, defUp.shareName),
            remotePath = p.getString(K_UP_PATH, defUp.remotePath).orEmpty().trim(),
            apkFileName = s(K_UP_FILE, defUp.apkFileName),
            username = s(K_UP_USER, defUp.username),
            password = secret(ctx).getString(K_UP_PASS, "").orEmpty()
                .ifEmpty { defUp.password },
            domain = p.getString(K_UP_DOMAIN, "").orEmpty().trim(),
            autoCheck = p.getBoolean(K_UP_AUTO, defUp.autoCheck),
            checkIntervalHours = p.getInt(K_UP_HOURS, defUp.checkIntervalHours)
        )
    }

    fun saveUpdate(ctx: Context, c: UpdateConfig) {
        sp(ctx).edit()
            .putString(K_UP_HOST, c.host.trim())
            .putString(K_UP_SHARE, c.shareName.trim())
            .putString(K_UP_PATH, c.remotePath.trim())
            .putString(K_UP_FILE, c.apkFileName.trim())
            .putString(K_UP_USER, c.username.trim())
            .putString(K_UP_DOMAIN, c.domain.trim())
            .putBoolean(K_UP_AUTO, c.autoCheck)
            .putInt(K_UP_HOURS, c.checkIntervalHours)
            .apply()
        // Heslo zhodne s tym, co vlozil build, sa NEuklada: ulozena kopia by po
        // zmene v nas-credentials.local a prebuildovani prebila novu hodnotu
        // a appka by sa NAS-u hlasila starym heslom.
        secret(ctx).edit().apply {
            if (c.password.isEmpty() || c.password == BuildConfig.NAS_PASS) {
                remove(K_UP_PASS)
            } else {
                putString(K_UP_PASS, c.password)
            }
        }.apply()
    }

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong(K_UP_LAST, 0L)

    fun setLastUpdateCheck(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(K_UP_LAST, at).apply()
    }
}
