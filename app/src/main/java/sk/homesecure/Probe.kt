package sk.homesecure

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Zistuje, ci je dana IP na sieti.
 *
 * ARP tabulku (/proc/net/arp) Android od verzie 10 bez rootu precitat nedovoli,
 * takze pritomnost overujeme aktivne. Poradie metod je zamerne:
 *
 *  1. ICMP echo cez InetAddress.isReachable,
 *  2. systemovy ping - to iste, len inou cestou; niektore zariadenia maju
 *     poskladany sietovy stack tak, ze prve prejde len jedno z dvoch,
 *  3. TCP "zaklopanie" - VOLITELNE a standardne vypnute, vid nizsie.
 *
 * Prve dve metody su presne to, co si clovek overi rucne prikazom ping, takze
 * appka a uzivatel vidia to iste. To je dolezitejsie, nez chytit kazdy okrajovy
 * pripad.
 *
 * Vsetko blokujuce - volaj z IO vlakna.
 */
object Probe {

    /** Na tychto portoch nic bezat nemusi - staci nam odpoved "connection refused". */
    private val KNOCK_PORTS = intArrayOf(62078, 5555, 80)

    /** Vysledok sondovania aj s tym, ktora metoda zabrala - kvoli ladeniu. */
    data class Hit(val up: Boolean, val method: String = "")

    fun probe(ip: String, timeoutMs: Int = 1200, allowTcpKnock: Boolean = false): Hit {
        if (ip.isBlank()) return Hit(false)
        if (icmp(ip, timeoutMs)) return Hit(true, "ICMP")
        if (pingBinary(ip, timeoutMs)) return Hit(true, "ping")
        if (allowTcpKnock) {
            val port = tcpKnock(ip, minOf(timeoutMs, 700))
            if (port != null) return Hit(true, "TCP:$port")
        }
        return Hit(false)
    }

    fun isUp(ip: String, timeoutMs: Int = 1200, allowTcpKnock: Boolean = false): Boolean =
        probe(ip, timeoutMs, allowTcpKnock).up

    private fun icmp(ip: String, timeoutMs: Int): Boolean = try {
        InetAddress.getByName(ip).isReachable(timeoutMs)
    } catch (t: Throwable) {
        false
    }

    /**
     * Zaklopanie na par portov; aj odmietnute spojenie (RST) sa berie ako dokaz,
     * ze zariadenie zije.
     *
     * Preco je to standardne vypnute: ten predpoklad plati len na cistej LAN.
     * Router, brana alebo cokolvek robiace proxy-ARP vie poslat RST aj za
     * adresu, na ktorej nic nie je - a potom appka tvrdi "si doma", zatial co
     * ping z pocitaca na tu istu adresu mlci. Presne to sa aj stalo. Zapni to
     * len vtedy, ked ti zariadenie filtruje ICMP a overis si, ze vypnuty stav
     * hlasi spravne.
     *
     * @return port, ktory odpovedal, alebo null
     */
    private fun tcpKnock(ip: String, timeoutMs: Int): Int? {
        for (port in KNOCK_PORTS) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), timeoutMs)
                }
                return port
            } catch (e: ConnectException) {
                return port
            } catch (t: Throwable) {
                // Timeout alebo "no route to host" - skus dalsi port.
            }
        }
        return null
    }

    /**
     * Systemovy ping.
     *
     * Vystup zamerne necitame. Skorsia verzia robila readBytes() pred waitFor()
     * a tym sa cely sken zasekol: readBytes cita az do konca procesu, takze ked
     * ping na neodpovedajucu adresu cakal donekonecna, timeout na dalsom riadku
     * sa uz nemal ako uplatnit. Jeden riadok vystupu sa do rury zmesti, takze
     * necitanim sa nic nezablokuje.
     *
     * -w je celkovy deadline, -W cakanie na odpoved; niektore implementacie
     * (toybox/busybox) poznaju len jedno z nich, preto davame obe.
     */
    private fun pingBinary(ip: String, timeoutMs: Int): Boolean {
        var proc: Process? = null
        return try {
            val waitSec = maxOf(1, (timeoutMs + 999) / 1000)
            proc = ProcessBuilder(
                "/system/bin/ping", "-c", "1", "-w", "$waitSec", "-W", "$waitSec", ip
            ).redirectErrorStream(true).start()

            val finished = proc.waitFor(waitSec + 1L, TimeUnit.SECONDS)
            if (finished) proc.exitValue() == 0 else false
        } catch (e: IOException) {
            false
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { proc?.destroyForcibly() }
            runCatching { proc?.inputStream?.close() }
        }
    }

    /**
     * Je straziaci telefon sam na Wi-Fi? Ked nie je, skenovanie nema vypovednu
     * hodnotu a stav sa nesmie menit.
     */
    fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        }

        // activeNetwork byva null napriklad na telefone bez SIM pripojenom na
        // Wi-Fi bez internetu - system taku siet nepovazuje za "aktivnu". Nam
        // pritom uplne staci, ze na nejakej Wi-Fi visime, lebo skenujeme LAN.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
