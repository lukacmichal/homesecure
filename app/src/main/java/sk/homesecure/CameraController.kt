package sk.homesecure

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Obal nad CameraX: analyza snimkov (detekcia pohybu) + fotenie v plnom
 * rozliseni.
 *
 * Kamera sa otvara az pri zapnuti strazenia a pri vypnuti sa hned zavrie -
 * jednak preto, aby appka nedrzala kameru, kym si doma, jednak preto, aby si
 * mohol na telefone normalne pouzivat fotoaparat alebo videohovor bez toho, aby
 * sa o zariadenie bili.
 */
class CameraController(
    private val ctx: Context,
    private val owner: LifecycleOwner
) {

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var detector: MotionDetector? = null
    private var analysisExecutor: ExecutorService? = null

    /** Zavola sa z analyzacneho vlakna, ked sa v zabere nieco pohne. */
    @Volatile
    var onMotion: (() -> Unit)? = null

    /**
     * Kym je false, snimky sa analyzuju, ale pohyb sa ignoruje. Pouziva sa
     * pocas fotenia davky a nasledneho cooldownu.
     */
    @Volatile
    var motionEnabled: Boolean = true

    @Volatile
    var isRunning: Boolean = false
        private set

    suspend fun start(cfg: DetectConfig): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        return try {
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(ctx).get()
            }
            provider = cameraProvider

            val motionDetector = MotionDetector(cfg.sensitivity)
            detector = motionDetector
            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                // Stare snimky nas nezaujimaju, vzdy chceme ten posledny.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { image ->
                try {
                    val moved = motionDetector.feed(image)
                    if (moved && motionEnabled) onMotion?.invoke()
                } catch (t: Throwable) {
                    EventLog.log("Chyba pri analyze snimku: ${t.message}")
                } finally {
                    image.close()
                }
            }

            // Pri nule berieme, co kamera vie najlepsie; inak mierime na zadanu
            // sirku a chybu robime smerom NAHOR - na fotke z 3-4 metrov je kazdy
            // pixel navyse rozdiel medzi "nieco sa mihlo" a rozoznatelnou tvarou.
            val captureStrategy = if (cfg.photoWidth <= 0) {
                ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            } else {
                ResolutionStrategy(
                    Size(cfg.photoWidth, cfg.photoWidth * 3 / 4),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(
                    if (cfg.useFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                )
                .setJpegQuality(cfg.jpegQuality.coerceIn(60, 100))
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(captureStrategy)
                        .build()
                )
                .build()
            imageCapture = capture

            val selector =
                if (cfg.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            withContext(Dispatchers.Main) {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, selector, analysis, capture)
            }

            motionDetector.reset()
            isRunning = true

            // Zapisujeme skutocne vybrane rozlisenie: pozadovana hodnota je len
            // navrh a kamera casto vrati nieco ine. Bez tohto sa neda zistit,
            // preco su fotky mensie, nez clovek cakal.
            val chosen = capture.resolutionInfo?.resolution
            EventLog.log(
                "Kamera beží – fotky ${chosen?.let { "${it.width}×${it.height}" } ?: "?"}, " +
                    "kvalita ${cfg.jpegQuality.coerceIn(60, 100)}"
            )

            Result.success(Unit)
        } catch (t: Throwable) {
            // Najcastejsie: kameru prave drzi ina appka, alebo sluzba nedostala
            // pravo na kameru, lebo ju system startoval z pozadia.
            stop()
            Result.failure(t)
        }
    }

    suspend fun stop() {
        isRunning = false
        val cameraProvider = provider
        if (cameraProvider != null) {
            runCatching { withContext(Dispatchers.Main) { cameraProvider.unbindAll() } }
        }
        provider = null
        imageCapture = null
        detector = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    fun resetDetector() {
        detector?.reset()
    }

    /** Podiel zmenenych buniek v poslednom snimku - zobrazuje sa pri ladeni. */
    fun lastChangedCells(): Int = detector?.lastChangedCells ?: 0

    /** Odfoti jednu snimku do daneho suboru; null = fotenie zlyhalo. */
    suspend fun capture(target: File): File? {
        val capture = imageCapture ?: return null
        target.parentFile?.mkdirs()
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(target)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        EventLog.log("Fotenie zlyhalo: ${exception.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }
}
