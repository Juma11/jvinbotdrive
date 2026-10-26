package com.vintechafrica.jvinbuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * x: -1 .. 1, your left .. your right (camera image mirrored, like a mirror).
 * y: -1 (top) .. 1 (bottom). size: face width as a fraction of frame width.
 */
data class FaceInfo(
    val x: Float,
    val y: Float,
    val size: Float,
    val count: Int = 1,
    /** 0..1, or -1 if unknown */
    val smile: Float = -1f,
    /** lower of the two eyes, 0 (closed) .. 1 (open), or -1 if unknown */
    val eyesOpen: Float = -1f,
)

/**
 * The robot's eyes. Front camera at low resolution, tuned for the Tecno Spark 4.
 * With hand gestures on, frames alternate: one for faces, one for hands.
 * Results are delivered on the main thread.
 */
class FaceTracker(
    private val onFace: (FaceInfo?) -> Unit,
    private val hands: HandGestures?,
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            // Smile and eyes-open scores: smile mirroring and the staring contest.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var frameIndex = 0L

    @Volatile var fps = 0f
        private set
    private var frames = 0
    private var windowStart = SystemClock.elapsedRealtime()

    fun start(context: Context, owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val bitmap = upright(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            frameIndex++
            val h = hands
            // Hands get 2 of every 3 frames while a hand is in view, else every other frame.
            val handActive = h != null && SystemClock.elapsedRealtime() - h.lastHandAt < 1000
            val handFrame = if (handActive) frameIndex % 3L != 0L else frameIndex % 2L == 0L
            if (h != null && h.enabled && handFrame) {
                h.process(bitmap)
            } else {
                detectFace(bitmap)
            }
            countFrame()
        } catch (e: Exception) {
            Log.w(TAG, "Frame failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun detectFace(bitmap: Bitmap) {
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            emptyList()
        }
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val info = face?.let {
            FaceInfo(
                x = (1f - (it.boundingBox.exactCenterX() / width) * 2f).coerceIn(-1f, 1f),
                y = ((it.boundingBox.exactCenterY() / height) * 2f - 1f).coerceIn(-1f, 1f),
                size = it.boundingBox.width() / width,
                count = faces.size,
                smile = it.smilingProbability ?: -1f,
                eyesOpen = minOf(it.leftEyeOpenProbability ?: -1f, it.rightEyeOpenProbability ?: -1f),
            )
        }
        main.post { onFace(info) }
    }

    private fun upright(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun countFrame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        val dt = now - windowStart
        if (dt >= 1000) {
            fps = frames * 1000f / dt
            frames = 0
            windowStart = now
        }
    }

    fun shutdown() {
        detector.close()
        executor.shutdown()
    }

    companion object { private const val TAG = "FaceTracker" }
}
