package com.sankalp.infilectarobjectselector

import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.PixelCopy
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.gson.Gson
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.ar.core.Session
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import kotlin.collections.get

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var arSceneView: ARSceneView
    private lateinit var overlay: BoundingBoxOverlay
    private lateinit var detectorHelper: ObjectDetectorHelper

    private val prefsName = "anchors_prefs"
    private val savedKey = "saved_anchor_poses"
    private val gson = Gson()

    // PixelCopy thread for screenshots
    private val pixelCopyThread = HandlerThread("PixelCopyThread").apply { start() }
    private val pixelHandler = Handler(pixelCopyThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        overlay = findViewById(R.id.overlay)
        findViewById<Button?>(R.id.btn_snapshot)?.setOnClickListener { captureAndDetect() }

        // Initialize detector (uses your MediaPipe helper)
        detectorHelper = ObjectDetectorHelper(
            context = this,
            threshold = 0.4f,
            maxResults = 5,
            listener = this
        )

        // When overlay box tapped, attempt to place anchor at that screen coordinate
        overlay.onBoxTap = { box, x, y ->
            placeAnchorAtScreenPoint(x, y, box.label)
        }

        // Periodic capture loop (adjust interval as needed)
        startPeriodicCapture(800L)

        // When AR session available, restore anchors
        arSceneView.onSessionCreated = { session ->
            restoreSavedAnchors(session)
        }
    }

    // ---------------------
    // ObjectDetectorListener
    // ---------------------
    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, "Detector error: $error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(bundle: ObjectDetectorHelper.ResultBundle) {
        // bundle.results is List<ObjectDetectorResult>
        runOnUiThread {
            overlay.setDetections(bundle, bundle.results)
        }
    }

    // ---------------------
    // Capture & Detection
    // ---------------------
    private fun startPeriodicCapture(intervalMs: Long) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                captureAndDetect()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable)
    }

    private fun captureAndDetect() {
        // Capture ARSceneView content via PixelCopy
        val view = arSceneView
        if (view.width == 0 || view.height == 0) return

        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

        // PixelCopy needs a Window token; the ARSceneView's surface is in the same window.
        PixelCopy.request(view, bmp, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                // Offload heavy detection to background thread (MediaPipe internally handles delegates).
                lifecycleScope.launch(Dispatchers.Default) {
                    // rotationDegrees = 0 for now; adjust if your detector expects rotated input
                    detectorHelper.detectBitmap(bmp, rotationDegrees = 0)
                }
            } else {
                Log.w(TAG, "PixelCopy failed: $copyResult")
            }
        }, pixelHandler)
    }

    // ---------------------
    // AR: hit test -> anchor -> view node
    // ---------------------
    private fun placeAnchorAtScreenPoint(screenX: Float, screenY: Float, label: String) {
        val session: Session = arSceneView.session ?: run {
            Toast.makeText(this, "AR session not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Use session.update() to get current Frame and run hitTest(screenX, screenY)
        val frame: Frame = try {
            session.update()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to update AR session: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val hits = frame.hitTest(screenX, screenY)
        if (hits.isEmpty()) {
            Toast.makeText(this, "No surface hit. Try another spot.", Toast.LENGTH_SHORT).show()
            return
        }

        // Prefer plane hits that are inside polygon
        val bestHit = hits.firstOrNull { hit ->
            val t = hit.trackable
            t is Plane && t.isPoseInPolygon(hit.hitPose)
        } ?: hits[0]

        val anchor = bestHit.createAnchor()
        addAnchorNode(anchor, label)
        saveAnchorPose(anchor, label)
    }

    private fun addAnchorNode(anchor: Anchor, label: String) {
        // engine and scene from ARSceneView
        val engine = arSceneView.engine
        val scene = arSceneView.scene

        // Create AnchorNode with engine + anchor
        val anchorNode = AnchorNode(engine, anchor).apply {
            isEditable = false
        }

        // Add to scene
        // SceneView provides addChildNode; AnchorNode is a SceneNode, so use scene.addChildNode or arSceneView.addChildNode
        arSceneView.addChildNode(anchorNode)

        // Attach an Android view into the AR world using ViewAttachmentManager + ViewNode
        lifecycleScope.launch {
            val attachmentManager = ViewAttachmentManager(this@MainActivity, arSceneView)
            // must call onResume so manager registers lifecycle observers internally
            attachmentManager.onResume()

            val viewNode = ViewNode(
                engine = engine,
                modelLoader = arSceneView.modelLoader,
                viewAttachmentManager = attachmentManager
            )

            viewNode.loadView(
                context = this@MainActivity,
                layoutResId = R.layout.anchor_label,
                onLoaded = { instance, view ->

                    // Set your text
                    view.findViewById<TextView>(R.id.anchorText)?.text = label

                    // Attach ViewNode under anchor
                    anchorNode.addChildNode(viewNode)

                    // Offset above anchor
                    viewNode.position = Position(0f, 0.05f, 0f)
                },
                onError = { e ->
                    Log.e("AR", "Failed to load view: ${e.message}")
                }
            )

        }
    }

    // ---------------------
    // Save & restore anchor poses (simple local persistence)
    // ---------------------
    private data class SavedPose(
        val id: String,
        val tx: Float, val ty: Float, val tz: Float,
        val qx: Float, val qy: Float, val qz: Float, val qw: Float
    )

    private fun saveAnchorPose(anchor: Anchor, id: String) {
        val p = anchor.pose
        val saved = SavedPose(id, p.tx(), p.ty(), p.tz(), p.qx(), p.qy(), p.qz(), p.qw())
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val existingJson = prefs.getString(savedKey, "[]")
        val existing = gson.fromJson(existingJson, Array<SavedPose>::class.java)?.toMutableList() ?: mutableListOf()
        existing.add(saved)
        prefs.edit().putString(savedKey, gson.toJson(existing)).apply()
    }

    private fun restoreSavedAnchors(session: Session) {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val json = prefs.getString(savedKey, "[]") ?: "[]"
        val arr = gson.fromJson(json, Array<SavedPose>::class.java) ?: return

        for (sp in arr) {
            val pose = Pose(floatArrayOf(sp.tx, sp.ty, sp.tz), floatArrayOf(sp.qx, sp.qy, sp.qz, sp.qw))
            try {
                val anchor = session.createAnchor(pose)
                addAnchorNode(anchor, sp.id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create anchor from saved pose: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pixelCopyThread.quitSafely()
    }

    companion object {
        private const val TAG = "MainActivitySceneView"
    }
}
