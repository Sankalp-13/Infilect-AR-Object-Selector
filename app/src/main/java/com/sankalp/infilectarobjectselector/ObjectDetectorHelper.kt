package com.sankalp.infilectarobjectselector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ObjectDetectorHelper(
    val context: Context,
    var threshold: Float = 0.45f,
    var maxResults: Int = 3,
    var delegate: Int = DELEGATE_GPU,
    val listener: DetectorListener
) {

    private var detector: ObjectDetector? = null
    private var rotation = 0
    private lateinit var options: ImageProcessingOptions
    private val frameIndex = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)

    init { setup() }

    private fun setup() {

        frameIndex.set(0)
        inFlight.set(false)

        val base = BaseOptions.builder()
            .setModelAssetPath("efficientdet-lite0.tflite")
            .setDelegate(if (delegate == DELEGATE_GPU) Delegate.GPU else Delegate.CPU)
            .build()

        options = ImageProcessingOptions.builder()
            .setRotationDegrees(this@ObjectDetectorHelper.rotation)
            .build()

        val detOpts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(maxResults)
            .setScoreThreshold(threshold)
            .setResultListener(this::deliverResult)
            .setErrorListener(this::deliverError)
            .build()

        detector = ObjectDetector.createFromOptions(context, detOpts)
    }

    fun updateThreshold(newValue: Float) {
        threshold = newValue
        setup()
    }

    fun detectBitmap(bitmap: Bitmap, rotationDegrees: Int = 0) {
        // Try to claim slot; if busy, drop this bitmap (prevent queue backlog)
        if (!inFlight.compareAndSet(false, true)) {
            return
        }

        if (rotationDegrees != this@ObjectDetectorHelper.rotation) {
            this@ObjectDetectorHelper.rotation = rotationDegrees
            detector?.close()
            setup()
            // release inFlight because we didn't call detect
            inFlight.set(false)
            return
        }

        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()

        val t = frameIndex.incrementAndGet()
        try {
            detector?.detectAsync(mpImage, options, t)
        } catch (e: Exception) {
            inFlight.set(false)
            listener.onError("detectAsync failed: ${e.message}")
        }
    }
    private fun deliverResult(res: ObjectDetectorResult, img: MPImage) {
        inFlight.set(false)

        val time = SystemClock.uptimeMillis() - res.timestampMs()
        listener.onResults(ResultBundle(listOf(res), time, img.height, img.width,
            this@ObjectDetectorHelper.rotation
        ))
    }
    private fun deliverError(e: RuntimeException) {
        inFlight.set(false)
        listener.onError(e.message ?: "Unknown error")
    }
    data class ResultBundle(
        val results: List<ObjectDetectorResult>,
        val time: Long,
        val height: Int,
        val width: Int,
        val rotation: Int
    )

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(bundle: ResultBundle)
    }

    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
    }
}
