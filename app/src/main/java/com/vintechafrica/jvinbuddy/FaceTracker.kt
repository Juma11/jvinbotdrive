package com.vintechafrica.jvinbuddy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/** x: -1 (left of frame) .. 1 (right). size: face width as a fraction of frame width. */
data class FaceInfo(val x: Float, val size: Float)

/**
 * Front camera + ML Kit face detection in fast mode on small frames.
 * Tuned for weak phones like the Tecno Spark 4.
 */
class FaceTracker(private val onResult: (FaceInfo?) -> Unit) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()
    )
    private val executor = Executors.newSingleThreadExecutor()

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
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val image = InputImage.fromMediaImage(media, rotation)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    onResult(null)
                } else {
                    val cx = face.boundingBox.exactCenterX() / width
                    onResult(
                        FaceInfo(
                            x = (cx * 2f - 1f).coerceIn(-1f, 1f),
                            size = face.boundingBox.width().toFloat() / width
                        )
                    )
                }
                countFrame()
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { proxy.close() }
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
