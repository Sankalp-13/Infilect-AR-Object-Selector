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

    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String,
        val score: Float
    )

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
    fun setDetections(
        bundle: ObjectDetectorHelper.ResultBundle,
        results: List<ObjectDetectorResult>
    ) {
        boxes.clear()

        imageWidth = bundle.width
        imageHeight = bundle.height
        rotation = bundle.rotation

        // Loop ALL detections in ALL result items
        for (res in results) {
            for (det in res.detections()) {

                val bbox = det.boundingBox()
                val left = bbox.left
                val top = bbox.top
                val right = bbox.right
                val bottom = bbox.bottom

                val screenRect = imageRectToViewRect(left, top, right, bottom)

                val label = det.categories().firstOrNull()?.categoryName() ?: "Obj"
                val score = det.categories().firstOrNull()?.score() ?: 0f

                boxes.add(
                    Box(
                        screenRect.left,
                        screenRect.top,
                        screenRect.right,
                        screenRect.bottom,
                        label,
                        score
                    )
                )
            }
        }

        postInvalidate()
    }

    fun imageRectToViewRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val left = if (l <= 1f) l * imageWidth else l
        val top = if (t <= 1f) t * imageHeight else t
        val right = if (r <= 1f) r * imageWidth else r
        val bottom = if (b <= 1f) b * imageHeight else b

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)

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

            val labelText = "${b.label} ${"%.2f".format(b.score)}"
            val textW = paintText.measureText(labelText)
            val textH = paintText.textSize

            canvas.drawRect(
                b.left,
                b.top - textH,
                b.left + textW + 8f,
                b.top,
                paintTextBg
            )
            canvas.drawText(labelText, b.left + 4f, b.top - 4f, paintText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            val hit = boxes.firstOrNull { box ->
                x >= box.left && x <= box.right &&
                        y >= box.top && y <= box.bottom
            }

            hit?.let { onBoxTap?.invoke(it, x, y) }
        }
        return true
    }
}
