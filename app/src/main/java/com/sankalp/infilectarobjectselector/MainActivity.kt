package com.sankalp.infilectarobjectselector

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sankalp.infilectarobjectselector.databinding.ActivityMainBinding
import java.util.concurrent.Executors
class MainActivity : ComponentActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: ObjectDetectorHelper
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsIfNeeded()

        detector = ObjectDetectorHelper(this, listener = this)

        setupCamera()
    }
    private fun setupCamera() {
        binding.previewView.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(executor, detector::detectLivestreamFrame)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            }, ContextCompat.getMainExecutor(this))
        }
    }

    // MEDIAPIPE CALLBACK
    override fun onResults(bundle: ObjectDetectorHelper.ResultBundle) {
        runOnUiThread {
            val result = bundle.results[0]
            binding.overlay.setResults(result, bundle.height, bundle.width, bundle.rotation)
            binding.overlay.invalidate()
        }
    }

    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    // PERMISSIONS
    private fun requestPermissionsIfNeeded() {
        val perms = arrayOf(Manifest.permission.CAMERA)

        if (!hasPermission(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, perms, 0)
        }
    }

    private fun hasPermission(perm: String) =
        ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}
