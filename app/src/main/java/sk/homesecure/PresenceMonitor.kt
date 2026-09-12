package sk.homesecure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Prevadza jednotlive vysledky pingu na stav "doma / prec".
 *
 * Podstatna je nesymetria: odchod vyhlasime az po niekolkych zmeskanych kolach
 * (telefon v spanku obcas odpoved zahodi a falosny poplach je horsi nez minuta
 * oneskorenia), ale navrat staci jedina uspesna odpoved - vtedy je dolezite
 * reagovat co najrychlejsie, lebo bezi ochranne okno nad uz spravenymi fotkami.
 */
class PresenceMonitor {

    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    /** Ako dopadlo jedno zariadenie vratane toho, ktora sonda zabrala. */
    data class DeviceResult(
        val device: TrustedDevice,
        val up: Boolean,
        val method: String
    )

    /** Vysledok jedneho kola aj s tym, ako dopadlo ktore zariadenie. */
    data class Round(
        val presence: Presence,
        val details: List<DeviceResult> = emptyList()
    )

    private companion object {
        /**
         * Tvrdy strop na jedno oslovenie zariadenia. Ziadna sondovacia metoda
         * nesmie zaseknut celu slucku - raz sa to uz stalo (systemovy ping cakal
         * na odpoved donekonecna) a prejavilo sa to tak, ze appka prestala
         * reagovat presne vtedy, ked zariadenie zo siete zmizlo.
         */
        const val PROBE_TIMEOUT_MS = 6_000L
    }

    var missedRounds: Int = 0
        private set

    var lastSeenDevice: String? = null
        private set

    fun reset() {
        missedRounds = 0
        lastSeenDevice = null
    }

    /**
     * Jedno kolo skenovania. Zariadenia testujeme paralelne, aby dlzka kola
     * nerastla s ich poctom - casom ich moze pribudat.
     */
    suspend fun poll(
        devices: List<TrustedDevice>,
        awayRounds: Int,
        allowTcpKnock: Boolean = false
    ): Round {
        if (devices.isEmpty()) {
            // Bez zoznamu nevieme rozlisit "doma" od "prec" - radsej nestrazime,
            // nez by sme fotili vlastnu obyvacku.
            missedRounds = 0
            return Round(Presence.UNKNOWN)
        }

        val results: List<DeviceResult> = withContext(Dispatchers.IO) {
            coroutineScope {
                devices.map { device ->
                    async {
                        val hit = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                            Probe.probe(device.ip, allowTcpKnock = allowTcpKnock)
                        } ?: Probe.Hit(false, "timeout")
                        DeviceResult(device, hit.up, hit.method)
                    }
                }.awaitAll()
            }
        }

        val hit = results.firstOrNull { it.up }?.device
        val presence = if (hit != null) {
            missedRounds = 0
            lastSeenDevice = hit.name
            Presence.PRESENT
        } else {
            missedRounds++
            if (missedRounds >= awayRounds) Presence.ABSENT else Presence.PRESENT
        }
        return Round(presence, results)
    }
}
