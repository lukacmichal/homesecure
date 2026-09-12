package sk.homesecure

import android.content.Context
import java.io.File

/**
 * Fotky na disku v dvoch priecinkoch, medzi ktorymi sa udalost presuva:
 *
 *   quarantine/  cerstvo odfotene, este bezi ochranne okno
 *   outbox/      okno uplynulo, caka sa uz len na odoslanie mailu
 *
 * Presun je obycajny rename, teda atomicky - keby proces spadol presne medzi
 * tymi dvoma krokmi, udalost ostane cela v jednom alebo v druhom priecinku
 * a nikdy nie rozpolena. Vsetko lezi v app-private ulozisku, takze sa to
 * neobjavi v galerii.
 */
class EventStore(ctx: Context) {

    private val root = ctx.applicationContext.filesDir
    private val quarantineDir = File(root, "quarantine")
    private val outboxDir = File(root, "outbox")

    init {
        quarantineDir.mkdirs()
        outboxDir.mkdirs()
    }

    data class Event(val id: String, val dir: File) {
        /** Cas vzniku je zakodovany v nazve, aby prezil restart procesu. */
        val capturedAt: Long
            get() = id.removePrefix("evt_").toLongOrNull() ?: dir.lastModified()

        val photos: List<File>
            get() = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedBy { it.name }
                ?: emptyList()

        val sizeBytes: Long
            get() = photos.sumOf { it.length() }
    }

    fun createQuarantine(now: Long = System.currentTimeMillis()): Event {
        val id = "evt_$now"
        val dir = File(quarantineDir, id)
        dir.mkdirs()
        return Event(id, dir)
    }

    fun listQuarantine(): List<Event> = list(quarantineDir)

    fun listOutbox(): List<Event> = list(outboxDir)

    private fun list(parent: File): List<Event> =
        parent.listFiles { f -> f.isDirectory && f.name.startsWith("evt_") }
            ?.map { Event(it.name, it) }
            ?.sortedBy { it.capturedAt }
            ?: emptyList()

    /** Ochranne okno uplynulo - udalost ide do fronty na odoslanie. */
    fun release(event: Event): Event? {
        val target = File(outboxDir, event.id)
        return if (event.dir.renameTo(target)) Event(event.id, target) else null
    }

    /** Prisiel si domov vcas - fotky mazeme, nikam sa neodoslu. */
    fun discard(event: Event) {
        event.dir.deleteRecursively()
    }

    /** Mail odisiel, fotky uz netreba drzat. */
    fun done(event: Event) {
        event.dir.deleteRecursively()
    }

    /**
     * Ked je dlhodobo bez internetu, fronta by inak zaplnila uloziste. Starsie
     * udalosti nad limit zahadzujeme - novsie su relevantnejsie.
     */
    fun prune(maxEvents: Int = 60, maxBytes: Long = 250L * 1024 * 1024) {
        val events = listOutbox()
        var total = events.sumOf { it.sizeBytes }
        var count = events.size
        for (e in events) {
            if (count <= maxEvents && total <= maxBytes) break
            total -= e.sizeBytes
            count--
            EventLog.log("Fronta je plna, zahadzujem starsiu udalost ${e.id}")
            e.dir.deleteRecursively()
        }
    }
}
