package com.sankalp.infilectarobjectselector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    val context: Context,
    var threshold: Float = 0.45f,
    var maxResults: Int = 3,
    var delegate: Int = DELEGATE_CPU,
    val listener: DetectorListener
) {

    private var detector: ObjectDetector? = null
    private var rotation = 0
    private lateinit var options: ImageProcessingOptions


    init { setup() }

    private fun setup() {
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

    fun detectLivestreamFrame(imageProxy: ImageProxy) {
        val t = SystemClock.uptimeMillis()

        val bmp = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bmp.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

        val rot = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        if (rot != this@ObjectDetectorHelper.rotation) {
            this@ObjectDetectorHelper.rotation = rot
            detector?.close()
            setup()
            return
        }

        val mpImage = BitmapImageBuilder(bmp).build()
        detector?.detectAsync(mpImage, options, t)
    }

    private fun deliverResult(res: ObjectDetectorResult, img: MPImage) {
        val time = SystemClock.uptimeMillis() - res.timestampMs()
        listener.onResults(ResultBundle(listOf(res), time, img.height, img.width,
            this@ObjectDetectorHelper.rotation
        ))
    }

    private fun deliverError(e: RuntimeException) {
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
