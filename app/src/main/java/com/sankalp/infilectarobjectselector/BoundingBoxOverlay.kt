package com.sankalp.infilectarobjectselector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max

class BoundingBoxOverlay(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String, val score: Float)

    private val paintBox = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
    }
    private val paintTextBg = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88000000")
    }
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 42f
    }

    private val boxes = mutableListOf<Box>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var rotation = 0

    var onBoxTap: ((Box, Float, Float) -> Unit)? = null

    fun setDetections(bundle: ObjectDetectorHelper.ResultBundle, results: List<ObjectDetectorResult>) {
        boxes.clear()
        imageWidth = bundle.width
        imageHeight = bundle.height
        rotation = bundle.rotation

        // Convert each detection's bounding box to view coordinates
        for (res in results) {
            val first = res.detections().firstOrNull() ?: continue
            val bbox = first.boundingBox()
            val left = bbox.left
            val top = bbox.top
            val right = bbox.right
            val bottom = bbox.bottom

            val screenRect = imageRectToViewRect(left, top, right, bottom)
            val label = first.categories()?.firstOrNull()?.categoryName() ?: "Obj"
            val score = first.categories()?.firstOrNull()?.score() ?: 0f

            boxes.add(Box(screenRect.left, screenRect.top, screenRect.right, screenRect.bottom, label, score))
        }

        postInvalidate()
    }

    public fun imageRectToViewRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val left = if (l <= 1f) l * imageWidth else l
        val top = if (t <= 1f) t * imageHeight else t
        val right = if (r <= 1f) r * imageWidth else r
        val bottom = if (b <= 1f) b * imageHeight else b

        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()
        val scale = max(scaleX, scaleY)

        // compute letterbox offsets
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        val vx1 = left * scale + offsetX
        val vy1 = top * scale + offsetY
        val vx2 = right * scale + offsetX
        val vy2 = bottom * scale + offsetY

        return RectF(vx1, vy1, vx2, vy2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in boxes) {
            canvas.drawRect(b.left, b.top, b.right, b.bottom, paintBox)
            val label = "${b.label} ${"%.2f".format(b.score)}"
            val textW = paintText.measureText(label)
            val textH = paintText.textSize
            canvas.drawRect(b.left, b.top - textH, b.left + textW + 8f, b.top, paintTextBg)
            canvas.drawText(label, b.left + 4f, b.top - 4f, paintText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val hit = boxes.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
            hit?.let {
                onBoxTap?.invoke(it, x, y)
            }
        }
        return true
    }
}
