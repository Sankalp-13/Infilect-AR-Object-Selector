package com.sankalp.infilectarobjectselector

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.internal.utils.ImageUtil.rotateBitmap
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.gson.Gson
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.ar.core.Session
import com.google.ar.core.Frame
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import io.github.sceneview.node.Node
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var arSceneView: ARSceneView
    private lateinit var overlay: BoundingBoxOverlay
    private lateinit var detectorHelper: ObjectDetectorHelper

    private var autoMode = false


    private val placedCheckmarks = mutableListOf<Node>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        overlay = findViewById(R.id.overlay)


        detectorHelper = ObjectDetectorHelper(
            context = this,
            threshold = 0.35f,
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
                node.lookAt(arSceneView.cameraNode.position, arSceneView.cameraNode.upDirection);

            }
        }
    }

    private fun enableVerticalShelfMode(session: Session) {
        arSceneView.configureSession { _, config ->
            config.instantPlacementMode =
                Config.InstantPlacementMode.LOCAL_Y_UP

            config.depthMode =
                if (session.isDepthModeSupported(Config .DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
    }

    @SuppressLint("RestrictedApi")
    private fun processFrame(frame: Frame) {
        val image = try { frame.acquireCameraImage() }
        catch (_: Exception) { return }

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

            if (!autoMode) return@runOnUiThread  // DO NOTHING


            for (res in bundle.results) {
                val det = res.detections().firstOrNull() ?: continue
                val box = det.boundingBox()



                // find screen center for this bbox
                val screenRect = overlay.imageRectToViewRect(
                    box.left, box.top, box.right, box.bottom
                )
                val cx = (screenRect.left + screenRect.right) / 2f
                val cy = (screenRect.top + screenRect.bottom) / 2f

                // Auto-select the object
                placeAnchorAtScreenPoint(cx, screenRect.bottom/1.1f)
            }
        }
    }


    private fun placeAnchorAtScreenPoint(x: Float, y: Float) {
        val session = arSceneView.session ?: return
        val frame = try { session.update() } catch (_: Exception) { return }

        val hits = frame.hitTest(x, y)
        if (hits.isEmpty()) return

        val hit = hits[0]
        val pose = hit.hitPose

        val newPos = Position(pose.tx(), pose.ty(), pose.tz())

        if (isTooCloseToExistingAnchors(newPos)) {
            Log.d("AR", "Skipped duplicate object (too close)")
            return
        }

        val anchor = hit.createAnchor()
        addAnchorNode(anchor)
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
                layoutResId = R.layout.checkmark_layout, // your checkmark
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

        // distance from camera to new object
        val dz = newPos.z - cameraPos.z
        val dy = newPos.y - cameraPos.y
        val dx = newPos.x - cameraPos.x
        val distToCamera = sqrt(dx*dx + dy*dy + dz*dz)

        // ---- Adaptive threshold ----
        val base = 0.12f     // 12 cm minimum
        val factor = 0.07f   // + 7 cm per meter of distance
        val threshold = base + factor * distToCamera

        for (node in placedCheckmarks) {
            val oldPos = node.worldPosition

            val ddx = oldPos.x - newPos.x
            val ddy = oldPos.y - newPos.y
            val ddz = oldPos.z - newPos.z

            val dist = sqrt(ddx*ddx + ddy*ddy + ddz*ddz)

            if (dist < threshold) {
                Log.d("AR", "SKIPPED: too close ($dist m < $threshold m)")
                return true
            }
        }
        return false
    }
}
