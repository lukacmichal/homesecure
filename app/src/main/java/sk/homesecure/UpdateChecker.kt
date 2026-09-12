package sk.homesecure

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kontrola novej verzie na NAS-e.
 *
 * Verzia sa necita z nazvu suboru (ten je pevny), ale az z uz stiahnuteho APK.
 * Znamena to, ze pri kazdej kontrole tiahneme cely subor - pri par megabajtoch
 * na domacej sieti je to lacnejsie, nez udrziavat vedla toho este metadatovy
 * subor, ktory by sa mohol rozist s realitou.
 */
object UpdateChecker {

    sealed class Outcome {
        object NotConfigured : Outcome()
        object UpToDate : Outcome()
        data class Available(val apk: File, val version: Long) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun check(ctx: Context, cfg: UpdateConfig): Outcome = withContext(Dispatchers.IO) {
        if (cfg.host.isBlank() || cfg.username.isBlank()) return@withContext Outcome.NotConfigured

        try {
            val target = ApkInstaller.downloadTarget(ctx, cfg.apkFileName)
            SmbUpdater.download(cfg, target)
            Prenos.zapis(ctx, target.length())

            val remote = ApkInstaller.readVersionCode(ctx, target)
            if (remote == null) {
                target.delete()
                return@withContext Outcome.Failed("Stiahnutý súbor sa nedá prečítať ako APK.")
            }

            val current = ApkInstaller.currentVersionCode(ctx)
            if (remote > current) {
                Outcome.Available(target, remote)
            } else {
                // Netreba drzat kopiu rovnakej verzie v cache.
                target.delete()
                Outcome.UpToDate
            }
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Verzia lezaca na NAS-e. `toString()` je tvar, v ktorom ju vidi clovek. */
    data class Remote(val code: Long, val name: String) {
        override fun toString(): String = "$name ($code)"
    }

    /**
     * Lacne zistenie, co lezi na NAS-e - bez stahovania APK.
     *
     * Cita `output-metadata.json`, ktory Gradle kladie vedla APK; ma par stoviek
     * bajtov, takze sa to da spravit pri kazdom starte appky. Vracia null, len
     * ked sa na NAS nedalo pozriet.
     *
     * Vracia verziu VZDY, aj ked nie je novsia - paticka ma ukazat, co na NAS-e
     * naozaj lezi, nielen ked je to novsie. Rozidenie tohto suboru s realitou
     * nemoze nainstalovat nespravnu vec: [check] pred instalaciou aj tak cita
     * verziu z realneho APK.
     */
    suspend fun peekRemote(cfg: UpdateConfig): Remote? =
        withContext(Dispatchers.IO) {
            if (cfg.host.isBlank() || cfg.username.isBlank()) return@withContext null
            try {
                val e = org.json.JSONObject(SmbUpdater.readText(cfg, "output-metadata.json"))
                    .getJSONArray("elements")
                    .getJSONObject(0)
                Remote(e.getLong("versionCode"), e.optString("versionName").ifBlank { "?" })
            } catch (_: Throwable) {
                null
            }
        }
}
