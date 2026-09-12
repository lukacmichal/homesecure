package sk.homesecure

import android.content.Context
import android.os.Build
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Paticka s verziou — jediny prvok, ktory vyzera ROVNAKO vo vsetkych appkach
 * (docs/shared-standard.md, kapitola 2.6):
 *
 *     HomeSecure 1.6 (7) · NAS 1.6 (7)
 *
 * Vzdy uplne dole na hlavnej obrazovke, 11sp, tlmena farba, centrovane.
 * Tuknutim sa spusti rucna kontrola.
 *
 * Ked sa verzia na NAS-e nedala zistit (mimo domu, NAS vypnuty), je tam
 * pomlcka — nie prazdno. Prazdne miesto vyzera ako chyba vykreslenia,
 * pomlcka hovori "pozrelo sa a nedalo sa zistit".
 *
 * Tento subor je zamerna kopia: appky su samostatne Gradle projekty na roznych
 * diskoch, spolocny modul by znamenal krizne zavislosti medzi nimi. Ked sa meni,
 * meni sa vo vsetkych naraz.
 */
object VersionUi {

    /** "1.6 (7)" — tvar, v ktorom sa verzia ukazuje vsade. */
    fun installed(ctx: Context): String {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return "${info.versionName ?: "?"} ($code)"
    }

    /**
     * @param remote verzia na NAS-e, alebo null ked sa ju nepodarilo zistit
     * @param newer  je tá na NAS-e novsia? (riadok sa zvyrazni)
     */
    fun render(view: TextView, remote: String?, newer: Boolean = false) {
        val ctx = view.context
        view.text = ctx.getString(
            if (newer) R.string.ver_footer_new else R.string.ver_footer,
            ctx.getString(R.string.app_name),
            installed(ctx),
            remote ?: ctx.getString(R.string.ver_none),
        )
        view.setTextColor(
            ContextCompat.getColor(ctx, if (newer) R.color.ver_warn else R.color.ver_text)
        )
    }
}
