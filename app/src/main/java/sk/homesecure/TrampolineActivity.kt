package sk.homesecure

import android.app.Activity
import android.os.Bundle

/**
 * Neviditelna aktivita, ktora existuje jediny dovod: na zlomok sekundy dostat
 * proces do popredia a odtial nastartovat MonitorService.
 *
 * Bez nej by sluzba nastartovana po reboote alebo z budika nedostala pristup ku
 * kamere (Android 11+ ho sluzbam vzniknutym na pozadi odopiera). Aktivita nema
 * ziadny obsah, hned sa ukoncuje a nema animaciu, takze na obrazovke sa
 * neprejavi.
 */
class TrampolineActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { ServiceLauncher.startDirect(this) }
        finish()
        // Nastupca (overrideActivityTransition) je az od Androidu 14, tu nam ide
        // len o to, aby po sebe aktivita nenechala ziadne blikntutie.
        overridePendingTransition(0, 0)
    }
}
