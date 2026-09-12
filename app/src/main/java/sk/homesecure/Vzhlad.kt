package sk.homesecure

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Vzhlad appky: rezim (svetly/cierny) a velkost pisma.
 *
 * Tento subor je vo VSETKYCH appkach rovnaky (lisi sa len `package` a mena
 * farieb, ktore ma dana paleta) — rovnaka dohoda ako pri `NastaveniaForm.kt`,
 * viz docs/shared-standard.md. Ked sa tu nieco meni, meni sa to vsade.
 *
 * **Preco je tmavy rezim CIERNY, nie tmavosivy.** Dovod, kvoli ktoremu vznikol,
 * je vydrz baterie na OLED displeji: cierny pixel sa nerozsvieti vobec, kym
 * tmavosivy svieti takmer rovnako ako svetly. Tmavosiva paleta by teda vyzerala
 * moderne a neusetrila by nic. Karty a polia su preto len o kusok nad ciernou
 * (#0E0E0E), aby bolo vidiet ich hranicu, a plocha pozadia je ciste #000000.
 *
 * **Preco tri moznosti a nie vlastna farba.** Paleta appky nie je jedna farba,
 * ale osem hodnot, ktore musia drzat kontrast medzi sebou (text na pozadi,
 * zelena a cervena na oboch). Volny vyber farby by tie vztahy rozbil a appka
 * by skoncila s necitatelnym riadkom. Tri hotove rezimy robia to, kvoli comu
 * sa o farbu ziada — a nedaju sa nastavit zle.
 */
object Vzhlad {

    // --- rezim ---

    const val PODLA_SYSTEMU = 0
    const val SVETLY = 1
    const val CIERNY = 2

    // --- velkost pisma ---

    /** Krok = zhruba jeden bod na 15sp zaklade, teda ~7 %. */
    const val KROK_PISMA = 0.07f

    /**
     * Rozsah krokov pisma. Do 3. 9. 2026 to bolo -1 az +3, teda styri stupne
     * dokopy — a to bolo malo hlavne vo widgetoch, kde je najvacsi krok stale
     * len o tri body vyssi nez zakladne pismo. Rozsah je teraz -6 az +6:
     * plus sest je pri 15sp zaklade zhruba 21sp, co sa da precitat z dlzky
     * ruky, minus sest zhruba 9sp pre toho, kto chce na obrazovku dostat
     * viac riadkov.
     *
     * Ulozena hodnota sa vsade `coerceIn`-uje, takze zuzenie rozsahu spat
     * by nic nerozbilo — len by zvacsene pismo skocilo na novy strop.
     */
    const val PISMO_MIN = -6
    const val PISMO_MAX = 6

    private const val FILE = "vzhlad"
    private const val K_REZIM = "rezim"
    private const val K_PISMO = "pismo"
    private const val K_PISMO_WIDGET = "pismo_widget"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rezim(ctx: Context): Int =
        sp(ctx).getInt(K_REZIM, PODLA_SYSTEMU).coerceIn(PODLA_SYSTEMU, CIERNY)

    fun pismo(ctx: Context): Int =
        sp(ctx).getInt(K_PISMO, 0).coerceIn(PISMO_MIN, PISMO_MAX)

    /**
     * Krok pisma pre WIDGET — vlastny, nezavisly od appky (29. 8. 2026).
     *
     * Widget a appka sa citaju na inu vzdialenost: appku ma clovek v ruke
     * a pozera sa do nej, widget je jeden riadok medzi ikonami na plose,
     * casto cez pol obrazovky siroky. Jedno cislo pre oboje znamenalo, ze
     * zvacsene pismo v appke ubralo widgetu polovicu riadkov, a naopak.
     *
     * Predvolena hodnota je krok appky, nie nula: kto si uz raz pismo zvacsil,
     * ma ho mat zvacsene aj na ploche, kym nepovie inak.
     */
    fun pismoWidgetu(ctx: Context): Int =
        sp(ctx).getInt(K_PISMO_WIDGET, pismo(ctx)).coerceIn(PISMO_MIN, PISMO_MAX)

    fun uloz(
        ctx: Context,
        rezim: Int,
        pismo: Int,
        pismoWidgetu: Int = pismoWidgetu(ctx),
    ) {
        sp(ctx).edit()
            .putInt(K_REZIM, rezim.coerceIn(PODLA_SYSTEMU, CIERNY))
            .putInt(K_PISMO, pismo.coerceIn(PISMO_MIN, PISMO_MAX))
            .putInt(K_PISMO_WIDGET, pismoWidgetu.coerceIn(PISMO_MIN, PISMO_MAX))
            .apply()
        pouzi(ctx)
    }

    /**
     * Nasadi nocny rezim. Vola sa z `App.onCreate()`, teda raz za proces —
     * `AppCompatDelegate` si nastavenie pamata a sam prekresli vsetky beziace
     * obrazovky. Volanie z aktivity by fungovalo tiez, ale kazda by ho robila
     * znova a poradie prekresleni by zaviselo na tom, ktora sa otvorila prva.
     */
    fun pouzi(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (rezim(ctx)) {
                SVETLY -> AppCompatDelegate.MODE_NIGHT_NO
                CIERNY -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Nasobitel velkosti pisma pre dany krok. */
    fun faktor(krok: Int): Float = 1f + KROK_PISMA * krok.coerceIn(PISMO_MIN, PISMO_MAX)

    /**
     * Ako sa krok pisma vola v Nastaveniach.
     *
     * Je to tu, a nie v kazdej obrazovke zvlast, aby sa pri zmene rozsahu
     * nemuselo hladat po appkach, kde vsade sa to slovo sklada.
     */
    fun nazovKroku(krok: Int): String = when {
        krok == 0 -> "základná"
        krok > 0 -> "väčšie o $krok"
        else -> "menšie o ${-krok}"
    }

    fun faktor(ctx: Context): Float = faktor(pismo(ctx))

    /**
     * Nasobitel pisma pre widget. Widget kresli launcher, takze `fontScale`
     * z kontextu appky nepocuva — velkost sa mu musi povedat cislom
     * (`setTextViewTextSize`), a to cislo chodi odtialto.
     */
    fun faktorWidgetu(ctx: Context): Float = faktor(pismoWidgetu(ctx))

    /**
     * Kontext s prepocitanou velkostou pisma.
     *
     * Nasobi sa systemova hodnota, neprepisuje: kto ma v Androide zvacsene
     * pismo pre cely telefon, ma ho mat zvacsene aj tu a nastavenie appky ma
     * byt len doladenie navrch.
     *
     * Vola sa z `attachBaseContext`, teda skor nez sa nafukne akykolvek layout.
     * Neskorsia zmena `resources.configuration` uz na hotove View nema vplyv.
     */
    fun obal(zaklad: Context): Context {
        val krok = pismo(zaklad)
        if (krok == 0) return zaklad
        val c = Configuration(zaklad.resources.configuration)
        c.fontScale = c.fontScale * faktor(krok)
        return zaklad.createConfigurationContext(c)
    }

    /**
     * Podpis aktualneho nastavenia. Aktivita si ho pri vzniku odlozi a v
     * `onResume` porovna — ked sa lisi, prekresli sa. Bez toho by sa zmena
     * velkosti pisma prejavila az po zavreti a otvoreni appky (nocny rezim
     * si `AppCompatDelegate` zariadi sam, pismo nie).
     */
    fun podpis(ctx: Context): Int = rezim(ctx) * 100 + pismo(ctx)

    /** Je prave zapnuta tmava paleta? Pri "podla systemu" rozhoduje telefon. */
    fun jeTmavy(ctx: Context): Boolean = when (rezim(ctx)) {
        SVETLY -> false
        CIERNY -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Kontext, z ktoreho sa daju vytiahnut farby zvoleneho rezimu.
     *
     * Widget nema temu appky — kresli ho systemovy launcher a `values-night`
     * sa v nom riadi nastavenim TELEFONU, nie nastavenim appky. Bez tohto by
     * si clovek zapol cierny rezim v appke a widget by ostal biely. Farby sa
     * preto vo widgete nastavuju vyslovne (`setTextColor`, `setBackgroundColor`)
     * a beru sa odtialto.
     */
    fun temaKontext(ctx: Context): Context {
        val c = Configuration(ctx.resources.configuration)
        c.uiMode = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (jeTmavy(ctx)) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        return ctx.createConfigurationContext(c)
    }
}
