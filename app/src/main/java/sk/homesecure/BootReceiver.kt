package sk.homesecure

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Po reštarte telefónu (a po aktualizácii appky) rozbehne stráženie znova, ak
 * bolo zapnuté. Telefón sa občas reštartuje aj sám, napríklad po systémovej
 * aktualizácii, a vtedy nemá zmysel čakať, kým si to všimneš.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        EventLog.init(context)
        if (!Prefs.enabled(context)) return

        EventLog.log("Telefón naštartoval – obnovujem stráženie")
        ServiceLauncher.ensureRunning(context)
    }
}
