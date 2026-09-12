package sk.homesecure

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Aktualny stav strazenia. Sluzba a UI bezia v jednom procese, takze staci
 * zdielany singleton - netreba broadcast ani binder.
 */
object Status {

    enum class Mode {
        /** Strazenie je vypnute prepinacom. */
        OFF,

        /** Niektore z doveryhodnych zariadeni je na Wi-Fi - si doma. */
        DISARMED,

        /**
         * Nie je zadane ani jedno doveryhodne zariadenie, takze sa neda
         * rozhodnut, ci si doma. Musi mat vlastny stav - keby sa vykreslovalo
         * ako "doma", appka by pred uzivatelom schovala vlastnu zlu
         * konfiguraciu a vyzeralo by to, ze len nefunguje detekcia.
         */
        NO_DEVICES,

        /** Ziadne zariadenie nie je na sieti - kamera bezi. */
        ARMED,

        /** Nieco sa odfotilo a bezi ochranne okno. */
        GRACE
    }

    data class Snapshot(
        val mode: Mode = Mode.OFF,
        /** Meno zariadenia, ktore naposledy odpovedalo. */
        val seenDevice: String? = null,
        val lastProbeAt: Long = 0L,
        val missedRounds: Int = 0,
        val quarantined: Int = 0,
        val outbox: Int = 0,
        val cameraRunning: Boolean = false,
        val lastError: String? = null
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(block: (Snapshot) -> Snapshot) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = Snapshot()
    }
}
