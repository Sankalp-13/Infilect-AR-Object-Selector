package com.sankalp.infilectarobjectselector

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.internal.utils.ImageUtil.rotateBitmap
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.ar.core.Session
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import io.github.sceneview.node.Node
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var arSceneView: ARSceneView
    private lateinit var overlay: BoundingBoxOverlay
    private lateinit var detectorHelper: ObjectDetectorHelper

    private lateinit var tvDistance: TextView
    private lateinit var sbDistance: SeekBar
    private lateinit var sbThreshold: SeekBar
    private lateinit var tvThreshold: TextView

    private var currentThreshold = 0.35f


    private var autoMode = false
    private var fallbackDistance = 0.7f

    private val placedCheckmarks = mutableListOf<Node>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        overlay = findViewById(R.id.overlay)

        tvDistance = findViewById(R.id.tvDistance)
        sbDistance = findViewById(R.id.sbDistance)

        sbDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = (progress + 1) / 10f
                fallbackDistance = meters
                tvDistance.text = String.format("Fallback Depth: %.1f m", meters)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbThreshold = findViewById(R.id.sbThreshold)
        tvThreshold = findViewById(R.id.tvThreshold)

        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                currentThreshold = value
                tvThreshold.text = String.format("Model Threshold: %.2f", value)
                detectorHelper.updateThreshold(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        detectorHelper = ObjectDetectorHelper(
            context = this,
            threshold = currentThreshold,
            maxResults = 5,
            listener = this
        )

        overlay.onBoxTap = { box, x, y ->
            placeAnchorAtScreenPoint(x, box.bottom/1.1f )
        }

        val toggleBtn = findViewById<Button>(R.id.btnToggleMode)
        toggleBtn.setOnClickListener {
            autoMode = !autoMode
            toggleBtn.text = if (autoMode) "AUTO: ON" else "AUTO: OFF"
        }

        arSceneView.onSessionUpdated = { session, frame ->
            processFrame(frame)
        }

        arSceneView.onSessionCreated = { session ->
            enableVerticalShelfMode(session)
        }

        arSceneView.onFrame = {
            placedCheckmarks.forEach { node ->
                node.lookAt(arSceneView.cameraNode.position, arSceneView.cameraNode.upDirection)
            }
        }
    }
    private fun enableVerticalShelfMode(session: Session) {
        arSceneView.configureSession { _, config ->
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
    }

    @SuppressLint("RestrictedApi")
    private fun processFrame(frame: Frame) {
        val image = try { frame.acquireCameraImage() } catch (_: Exception) { return }
        val rawBitmap = imageToBitmap(image)
        val bitmap = rotateBitmap(rawBitmap, 90)
        image.close()
        lifecycleScope.launch(Dispatchers.Default) {
            detectorHelper.detectBitmap(bitmap, rotationDegrees = 0)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResults(bundle: ObjectDetectorHelper.ResultBundle) {
        runOnUiThread {
            overlay.setDetections(bundle, bundle.results)
            if (!autoMode) return@runOnUiThread

            for (res in bundle.results) {
                val det = res.detections().firstOrNull() ?: continue
                val box = det.boundingBox()
                val screenRect = overlay.imageRectToViewRect(
                    box.left, box.top, box.right, box.bottom
                )
                val cx = (screenRect.left + screenRect.right) / 2f
                val cy = (screenRect.top + screenRect.bottom) / 2f
                placeAnchorAtScreenPoint(cx, screenRect.bottom/1.1f)
            }
        }
    }

    private fun placeAnchorAtScreenPoint(x: Float, y: Float) {
        val frame = arSceneView.frame ?: return
        val hits = frame.hitTest(x, y)

        val bestHit = hits.firstOrNull { hit ->
            hit.trackable is com.google.ar.core.Plane && ((hit.trackable as com.google.ar.core.Plane).isPoseInPolygon(hit.hitPose))
        } ?: hits.firstOrNull { hit ->
            hit.trackable is com.google.ar.core.DepthPoint
        } ?: hits.firstOrNull { hit ->
            hit.trackable is com.google.ar.core.InstantPlacementPoint
        } ?: hits.firstOrNull()

        if (bestHit != null) {
            val pose = bestHit.hitPose
            val newPos = Position(pose.tx(), pose.ty(), pose.tz())
            if (isTooCloseToExistingAnchors(newPos)) return
            val anchor = bestHit.createAnchor()
            addAnchorNode(anchor)
        } else {
            // No change needed here, just logging
            // Log.w("AR", "HitTest failed. Using Raycast Fallback: $fallbackDistance m")
            placeFreeFloatingAnchor(x, y, frame)
        }
    }

    private fun placeFreeFloatingAnchor(x: Float, y: Float, frame: Frame) {

        val distanceMeters = fallbackDistance

        val ray = arSceneView.cameraNode.screenPointToRay(x, y)
        val origin = ray.origin
        val direction = ray.direction

        val posX = origin.x + (direction.x * distanceMeters)
        val posY = origin.y + (direction.y * distanceMeters)
        val posZ = origin.z + (direction.z * distanceMeters)

        val fallbackPose = Pose(
            floatArrayOf(posX, posY, posZ),
            floatArrayOf(0f, 0f, 0f, 1f)
        )

        val newPos = Position(posX, posY, posZ)
        if (isTooCloseToExistingAnchors(newPos)) return

        val session = arSceneView.session ?: return
        try {
            val anchor = session.createAnchor(fallbackPose)
            addAnchorNode(anchor)
        } catch (e: Exception) {
            Log.e("AR", "Failed to create fallback anchor", e)
        }
    }

    private fun addAnchorNode(anchor: Anchor) {
        val anchorNode = AnchorNode(arSceneView.engine, anchor)
        arSceneView.addChildNode(anchorNode)

        lifecycleScope.launch {
            val manager = ViewAttachmentManager(this@MainActivity, arSceneView)
            manager.onResume()

            val viewNode = ViewNode(
                engine = arSceneView.engine,
                modelLoader = arSceneView.modelLoader,
                viewAttachmentManager = manager
            )

            viewNode.loadView(
                context = this@MainActivity,
                layoutResId = R.layout.checkmark_layout,
                onLoaded = { _, _ ->
                    anchorNode.addChildNode(viewNode)
                    viewNode.position = Position(0f, 0f, 0f)
                    placedCheckmarks.add(viewNode)
                }
            )
        }
    }

    private fun isTooCloseToExistingAnchors(newPos: Position): Boolean {
        val cameraPos = arSceneView.cameraNode.worldPosition
        val dz = newPos.z - cameraPos.z
        val dy = newPos.y - cameraPos.y
        val dx = newPos.x - cameraPos.x
        val distToCamera = sqrt(dx*dx + dy*dy + dz*dz)

        val base = 0.12f
        val factor = 0.07f
        val threshold = base + factor * distToCamera

        for (node in placedCheckmarks) {
            val oldPos = node.worldPosition
            val ddx = oldPos.x - newPos.x
            val ddy = oldPos.y - newPos.y
            val ddz = oldPos.z - newPos.z
            val dist = sqrt(ddx*ddx + ddy*ddy + ddz*ddz)

            if (dist < threshold) return true
        }
        return false
    }
}