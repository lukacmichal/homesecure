package sk.homesecure

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Startovanie sluzby.
 *
 * Podstatny detail: sluzbu, ktora ma pracovat s kamerou, nesmieme na Androide
 * 11+ nastartovat priamo z pozadia (po reboote, z budika) - system by jej
 * pristup ku kamere odoprel a uz by sa to nedalo napravit inak nez restartom
 * sluzby. Preto z pozadia ideme cez neviditelnu aktivitu, ktora proces na
 * okamih vytiahne dopredu.
 */
object ServiceLauncher {

    /** Volaj z aktivity, teda vtedy, ked je appka uz v popredi. */
    fun startDirect(ctx: Context) {
        val intent = Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        ContextCompat.startForegroundService(ctx, intent)
    }

    /** Volaj z pozadia (boot receiver, watchdog). */
    fun ensureRunning(ctx: Context) {
        if (!Prefs.enabled(ctx)) return
        if (MonitorService.isRunning) return

        if (canDrawOverlays(ctx)) {
            val intent = Intent(ctx, TrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val ok = runCatching { ctx.startActivity(intent) }.isSuccess
            if (ok) return
        }

        // Bez povolenia "Zobrazovať cez iné aplikácie" ostava len priamy start.
        // Sluzba pobezi a siet skenovat bude, ale kamera sa nemusi podarit
        // otvorit, kym appku raz rucne neotvoris.
        EventLog.log("Štartujem službu z pozadia – kamera možno nebude dostupná")
        runCatching { startDirect(ctx) }
    }

    fun stop(ctx: Context) {
        val intent = Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
    }

    fun canDrawOverlays(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    fun openOverlaySettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}
