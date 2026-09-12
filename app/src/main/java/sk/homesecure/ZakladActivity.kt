package sk.homesecure

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Spolocny predok vsetkych obrazoviek: drzi vzhlad (rezim a velkost pisma).
 *
 * Tento subor je vo VSETKYCH appkach rovnaky (lisi sa len `package`) — rovnaka
 * dohoda ako pri `NastaveniaForm.kt` a `Vzhlad.kt`.
 *
 * Preco predok a nie tri riadky v kazdej aktivite: `attachBaseContext` sa da
 * prebit len v samotnej aktivite (kontext si kazda vyraba sama, `Application`
 * do toho nevidi). Jeden predok je jedine miesto, kde sa na to da zabudnut
 * naraz vo vsetkych.
 */
open class ZakladActivity : AppCompatActivity() {

    /** Nastavenie, s ktorym bola obrazovka postavena. */
    private var vzhladPodpis = 0

    override fun attachBaseContext(zaklad: Context) {
        super.attachBaseContext(Vzhlad.obal(zaklad))
    }

    override fun onCreate(stav: Bundle?) {
        vzhladPodpis = Vzhlad.podpis(this)
        super.onCreate(stav)
    }

    override fun onResume() {
        super.onResume()
        // Poistka pre pocitadlo dat: sietove kniznice appky su bez Androidu
        // a bajty len nazbieraju ([Prenos.nazbieraj]). Tu je prvy bod, kde je
        // po nich Context — bez tohto by prenos, ktory nikto vyslovne
        // neodovzdal, v pocitadle chybal.
        Prenos.odovzdaj(this)
        // Vratil sa z Nastaveni s inou velkostou pisma? Layout uz je nafukany
        // starym kontextom, takze jedina cesta je postavit obrazovku nanovo.
        if (Vzhlad.podpis(this) != vzhladPodpis) recreate()
    }
}
