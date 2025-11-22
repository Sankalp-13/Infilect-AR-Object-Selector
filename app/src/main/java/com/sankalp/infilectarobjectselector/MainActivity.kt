package com.sankalp.infilectarobjectselector

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.*
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.internal.utils.ImageUtil.rotateBitmap
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
//import com.google.ar.core.ImageFormat
import com.google.ar.core.Pose
import com.google.gson.Gson
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.ar.core.Session
import com.google.ar.core.Frame
//import com.google.ar.core.Image
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import io.github.sceneview.node.Node
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var arSceneView: ARSceneView
    private lateinit var overlay: BoundingBoxOverlay
    private lateinit var detectorHelper: ObjectDetectorHelper

    private val prefsName = "anchors_prefs"
    private val savedKey = "saved_anchor_poses"
    private val gson = Gson()

    private val placedCheckmarks = mutableListOf<Node>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        overlay = findViewById(R.id.overlay)


        detectorHelper = ObjectDetectorHelper(
            context = this,
            threshold = 0.4f,
            maxResults = 5,
            listener = this
        )

        overlay.onBoxTap = { box, x, y ->
            placeAnchorAtScreenPoint(x, y)
        }

        // FAST frame processing
        arSceneView.onSessionUpdated = { session, frame ->
            processFrame(frame)
        }

        arSceneView.onSessionCreated = { session ->
            enableVerticalShelfMode(session)
            restoreSavedAnchors(session)
        }

        arSceneView.onFrame = {
            placedCheckmarks.forEach { node ->
                node.lookAt(arSceneView.cameraNode.position)

            }
        }
    }

    private fun enableVerticalShelfMode(session: Session) {
        arSceneView.configureSession { _, config ->
            config.instantPlacementMode =
                Config.InstantPlacementMode.LOCAL_Y_UP

            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
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


    // Convert YUV → Bitmap
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
        }
    }

    private fun placeAnchorAtScreenPoint(x: Float, y: Float) {
        val session = arSceneView.session ?: return
        val frame = try { session.update() } catch (_: Exception) { return }

        val hits = frame.hitTest(x, y)
        if (hits.isEmpty()) {
            Toast.makeText(this, "No raycast hit", Toast.LENGTH_SHORT).show()
            return
        }

        // ⭐ DO NOT FILTER BY PLANE
        val hit = hits[0]    // includes vertical surface hits

        val anchor = hit.createAnchor()
        addAnchorNode(anchor)
        saveAnchorPose(anchor)
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

                    // track it to rotate toward camera
                    placedCheckmarks.add(viewNode)
                }
            )
        }
    }

    private data class SavedPose(
        val tx: Float, val ty: Float, val tz: Float,
        val qx: Float, val qy: Float, val qz: Float, val qw: Float
    )

    private fun saveAnchorPose(anchor: Anchor) {
        val p = anchor.pose
        val saved = SavedPose(
            p.tx(), p.ty(), p.tz(),
            p.qx(), p.qy(), p.qz(), p.qw()
        )

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val list = gson.fromJson(
            prefs.getString(savedKey, "[]"),
            Array<SavedPose>::class.java
        )?.toMutableList() ?: mutableListOf()

        list.add(saved)
        prefs.edit().putString(savedKey, gson.toJson(list)).apply()
    }

    private fun restoreSavedAnchors(session: Session) {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val saved = gson.fromJson(
            prefs.getString(savedKey, "[]"),
            Array<SavedPose>::class.java
        ) ?: return

        for (sp in saved) {
            val pose = Pose(
                floatArrayOf(sp.tx, sp.ty, sp.tz),
                floatArrayOf(sp.qx, sp.qy, sp.qz, sp.qw)
            )
            val anchor = runCatching { session.createAnchor(pose) }.getOrNull() ?: continue
            addAnchorNode(anchor)
        }
    }
}
