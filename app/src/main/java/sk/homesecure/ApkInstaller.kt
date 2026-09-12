package sk.homesecure

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Spustenie systemoveho instalatora nad stiahnutym APK.
 *
 * Android 8+ vyzaduje povolenie "Instalovat neznama aplikacie". Permission
 * REQUEST_INSTALL_PACKAGES v manifeste len umozni o to poziadat - samotne
 * zapnutie robi uzivatel v systemovych nastaveniach. Tiche instalovanie by
 * vyzadovalo device owner, co na tomto telefone zamerne nemame, lebo sluzi aj
 * ako prehravac hudby.
 */
object ApkInstaller {

    /** Aktualny versionCode tejto appky (na porovnanie s tym na NAS-e). */
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * Precita versionCode zo stiahnuteho APK bez instalacie - subor na NAS-e ma
     * vzdy rovnake meno, takze verzia sa neda zistit z nazvu. Null = subor sa
     * neda naparsovat ako APK (poskodeny download a pod.).
     */
    fun readVersionCode(context: Context, apk: File): Long? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** Ma appka povolene instalovat APK z neznamych zdrojov? */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Otvori systemovu obrazovku, kde sa povolenie zapina. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Odovzda APK systemovemu instalatoru cez FileProvider URI. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Kam ukladame stiahnute APK (zdielane cez FileProvider - vid file_paths.xml). */
    fun downloadTarget(context: Context, fileName: String): File =
        File(File(context.cacheDir, "updates"), fileName)
}
