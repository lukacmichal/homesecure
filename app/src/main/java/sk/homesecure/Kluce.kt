package sk.homesecure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Kluce k cudzim sluzbam (Netatmo, Alpaca, Gmail, mbenzin.cz) - nie su v APK.
 *
 * Do 12. 11. 2026 ich build vkladal z nas-credentials.local do BuildConfig, takze
 * ich vedel vytiahnut kazdy, kto mal subor APK v ruke: z NAS-u, z telefonu (aj
 * ina appka v nom) alebo z Googlu po kontrole Play Protect. Odvtedy lezia na NAS-e
 * vedla APK v [SUBOR] (zapisuje ho nahraj-na-nas.ps1) a appka si ich stiahne sama
 * do sifrovanych prefs. V APK ostava len prihlasenie k NAS-u - bez neho by sa
 * appka k suboru nedostala - a NAS je dostupny len doma a cez Tailscale.
 *
 * Spolocny subor (docs/shared-standard.md 2.2b): vo vsetkych appkach s klucmi doslova rovnaky
 * okrem riadku `package`. Ako sa subor z NAS-u precita, vie kazda appka po svojom
 * (vlastny SmbUpdater), preto [stiahni] dostava citanie ako funkciu.
 */
object Kluce {

    /** Subor v priecinku appky na NAS-e, riadky `MENO=hodnota`. */
    const val SUBOR = "kluce.txt"

    private const val TAG = "Kluce"
    private const val FILE = "kluce"

    @Volatile
    private var cache: SharedPreferences? = null

    /** Ulozeny kluc, alebo "" ked ho appka este nema. */
    fun hodnota(ctx: Context, meno: String): String =
        prefs(ctx).getString(meno, "").orEmpty()

    /** Chyba appke niektory z [mena]? */
    fun chyba(ctx: Context, mena: Collection<String>): Boolean =
        mena.any { hodnota(ctx, it).isBlank() }

    /**
     * Precita [SUBOR] cez [citaj] a ulozi kluce. Blokuje - volaj mimo hlavneho
     * vlakna. Nedostupny NAS je ticho (docs/shared-standard.md 2.4/5): vrati false a kluce,
     * ktore appka uz ma, ostanu.
     */
    fun stiahni(ctx: Context, citaj: () -> String): Boolean = try {
        uloz(ctx, citaj())
        true
    } catch (t: Throwable) {
        Log.i(TAG, "kluce z NAS-u sa nepodarilo nacitat: ${t.javaClass.simpleName}")
        false
    }

    /**
     * Ulozi kluce z obsahu [SUBOR] a vrati, kolko sa ich zmenilo. Prazdna hodnota
     * sa preskoci - neuplny subor na NAS-e nesmie zmazat kluc, ktory appka uz ma.
     * Do logu idu len mena, nikdy hodnoty.
     */
    fun uloz(ctx: Context, text: String): Int = synchronized(this) {
        val p = prefs(ctx)
        val zmenene = parsuj(text).filter { (meno, hodnota) -> p.getString(meno, null) != hodnota }
        if (zmenene.isNotEmpty()) {
            val e = p.edit()
            zmenene.forEach { (meno, hodnota) -> e.putString(meno, hodnota) }
            e.commit()
            Log.i(TAG, "nove kluce z NAS-u: ${zmenene.keys.sorted()}")
        }
        zmenene.size
    }

    /**
     * Tvar ako nas-credentials.local: delenie na prvom '=', hodnota orezana, bez
     * escapovania - heslo so spatnym lomitkom prejde tak, ako je. '#' je komentar.
     */
    fun parsuj(text: String): Map<String, String> {
        val bezBom = if (text.isNotEmpty() && text[0].code == 0xFEFF) text.substring(1) else text
        val out = LinkedHashMap<String, String>()
        for (surovy in bezBom.lineSequence()) {
            val riadok = surovy.trim()
            if (riadok.isEmpty() || riadok.startsWith("#")) continue
            val i = riadok.indexOf('=')
            if (i <= 0) continue
            val meno = riadok.substring(0, i).trim()
            val hodnota = riadok.substring(i + 1).trim()
            if (meno.isNotEmpty() && hodnota.isNotEmpty()) out[meno] = hodnota
        }
        return out
    }

    /** Sifrovane prefs; pri zlyhani keystore padni na obycajne (docs/shared-standard.md 2.3). */
    private fun prefs(ctx: Context): SharedPreferences {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = ctx.applicationContext
            val p = try {
                val key = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "sifrovane prefs nedostupne, pouzivam obycajne", t)
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            }
            cache = p
            return p
        }
    }
}
