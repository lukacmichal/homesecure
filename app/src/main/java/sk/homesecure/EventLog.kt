package sk.homesecure

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kratka historia toho, co appka robila. Je to jediny sposob, ako po navrate
 * domov zistit, preco sa nieco (ne)odfotilo - bez nej sa take veci ladia velmi
 * zle. Drzime poslednych MAX riadkov v pamati pre UI a to iste zrkadlime do
 * suboru, aby prezili restart procesu.
 */
object EventLog {
    private const val TAG = "HomeSecure"
    private const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 128 * 1024

    private val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var file: File? = null

    /** Nacita ulozenu historiu; vola sa raz pri starte procesu. */
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "events.log")
        file = f
        if (f.exists()) {
            runCatching { f.readLines().takeLast(MAX_LINES) }
                .onSuccess { _lines.value = it }
        }
    }

    fun log(msg: String) {
        val line = "${stamp.format(Date())}  $msg"
        Log.i(TAG, msg)
        synchronized(this) {
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
            appendToFile(line)
        }
    }

    private fun appendToFile(line: String) {
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            if (f.length() > MAX_FILE_BYTES) {
                // Orezeme na poslednu polovicu, nech subor nerastie donekonecna.
                val keep = f.readLines().takeLast(MAX_LINES / 2)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun clear() {
        synchronized(this) {
            _lines.value = emptyList()
            runCatching { file?.writeText("") }
        }
    }
}
