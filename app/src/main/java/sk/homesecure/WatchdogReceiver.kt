package sk.homesecure

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Budík, ktorý každých pár minút overí, že služba stále beží, a keď nie, zapne
 * ju znova. START_STICKY sám o sebe nestačí – viacerí výrobcovia (Xiaomi,
 * Samsung, Huawei) služby na pozadí agresívne ukončujú a späť ich nevrátia.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        if (!Prefs.enabled(context)) return
        if (MonitorService.isRunning) return

        EventLog.log("Služba nebežala – watchdog ju štartuje")
        ServiceLauncher.ensureRunning(context)
    }
}
