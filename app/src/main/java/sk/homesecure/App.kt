package sk.homesecure

import android.app.Application

/**
 * Vstupny bod procesu — appky aj widgetu (bezia v tom istom procese).
 *
 * Nocny rezim sa musi nasadit skor nez sa nakresli prva obrazovka; keby sa
 * nasadzoval az v aktivite, blikla by vo svetlom.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Vzhlad.pouzi(this)
    }
}
