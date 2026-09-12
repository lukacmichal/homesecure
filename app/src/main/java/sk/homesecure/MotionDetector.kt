package sk.homesecure

import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Detekcia pohybu porovnavanim jasu dvoch po sebe iducich snimkov.
 *
 * Snimok rozdelime na mriezku buniek a v kazdej spocitame priemerny jas
 * (Y zlozka - farbu vobec nepotrebujeme). Klucovy detail je odpocitanie
 * priemernej zmeny celeho obrazu: bez neho by kazde dovyrovnanie expozicie,
 * mrak pred slnkom alebo rozsvietena lampa vyzerali ako pohyb a chodili by ti
 * maily kazdych par minut. Po odpocitani ostane len lokalna zmena, teda nieco,
 * co sa v zabere naozaj pohlo.
 */
class MotionDetector(sensitivity: Int) {

    private companion object {
        const val GRID_W = 24
        const val GRID_H = 18
        const val CELLS = GRID_W * GRID_H

        /** Prvych par snimkov po starte kamery ma este rozkolisanu expoziciu. */
        const val WARMUP_FRAMES = 8
    }

    /** O kolko jasovych stupnov sa musi bunka zmenit, aby sa ratala. */
    private val cellThreshold: Float =
        6f + (100 - sensitivity.coerceIn(1, 100)) * 0.34f

    /** Kolko buniek naraz musi byt zmenenych, aby to bol pohyb (~2 % obrazu). */
    private val minChangedCells: Int = maxOf(3, CELLS / 50)

    private var previous: FloatArray? = null
    private var warmup = WARMUP_FRAMES

    /** Posledny podiel zmenenych buniek - pomaha pri ladeni citlivosti v UI. */
    @Volatile
    var lastChangedCells: Int = 0
        private set

    fun reset() {
        previous = null
        warmup = WARMUP_FRAMES
        lastChangedCells = 0
    }

    /** @return true, ak sa medzi tymto a predchadzajucim snimkom nieco pohlo. */
    fun feed(image: ImageProxy): Boolean {
        val current = sampleLuma(image) ?: return false
        val prev = previous
        previous = current

        if (prev == null) return false
        if (warmup > 0) {
            warmup--
            return false
        }

        var sum = 0f
        for (i in 0 until CELLS) sum += current[i] - prev[i]
        val globalShift = sum / CELLS

        var changed = 0
        for (i in 0 until CELLS) {
            if (abs((current[i] - prev[i]) - globalShift) > cellThreshold) changed++
        }
        lastChangedCells = changed
        return changed >= minChangedCells
    }

    /** Priemerny jas v kazdej bunke mriezky; null pri necakanom formate snimku. */
    private fun sampleLuma(image: ImageProxy): FloatArray? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (w < GRID_W || h < GRID_H) return null

        val cellW = w / GRID_W
        val cellH = h / GRID_H
        // Nepotrebujeme kazdy pixel - vzorkovanie drzi zataz na CPU nizko,
        // co je pri 24/7 behu na starom telefone podstatne.
        val stepX = maxOf(1, cellW / 4)
        val stepY = maxOf(1, cellH / 4)

        val out = FloatArray(CELLS)
        for (gy in 0 until GRID_H) {
            val yStart = gy * cellH
            for (gx in 0 until GRID_W) {
                val xStart = gx * cellW
                var total = 0
                var count = 0
                var y = yStart
                while (y < yStart + cellH && y < h) {
                    val rowOffset = y * rowStride
                    var x = xStart
                    while (x < xStart + cellW && x < w) {
                        val index = rowOffset + x * pixelStride
                        if (index < buffer.limit()) {
                            total += buffer.get(index).toInt() and 0xFF
                            count++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                out[gy * GRID_W + gx] = if (count > 0) total.toFloat() / count else 0f
            }
        }
        return out
    }
}
