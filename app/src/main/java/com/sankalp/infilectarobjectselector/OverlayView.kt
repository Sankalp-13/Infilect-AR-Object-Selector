package com.sankalp.infilectarobjectselector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var results: ObjectDetectorResult? = null
    private var boxes = mutableListOf<RectF>()
    private var outW = 0
    private var outH = 0
    private var rot = 0

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        results ?: return

        boxes.forEachIndexed { idx, rect ->
            canvas.drawRect(rect, boxPaint)

            val cat = results!!.detections()[idx].categories()[0]
            val text = "${cat.categoryName()} ${"%.2f".format(cat.score())}"

            canvas.drawRect(
                rect.left,
                rect.top - 45,
                rect.left + textPaint.measureText(text) + 16,
                rect.top,
                bgPaint
            )
            canvas.drawText(
                text,
                rect.left + 8,
                rect.top - 10,
                textPaint
            )
        }
    }

    fun setResults(res: ObjectDetectorResult, h: Int, w: Int, rotation: Int) {
        results = res
        outW = w
        outH = h
        rot = rotation

        val rotatedDims = if (rotation == 90 || rotation == 270)
            Pair(h, w)
        else
            Pair(w, h)

        val scaleX = width.toFloat() / rotatedDims.first
        val scaleY = height.toFloat() / rotatedDims.second
        val scale = maxOf(scaleX, scaleY)

        val dx = (width - rotatedDims.first * scale) / 2f   // horizontal gap
        val dy = (height - rotatedDims.second * scale) / 2f  // vertical gap


        boxes.clear()

        res.detections().forEach { det ->
            val r = RectF(det.boundingBox())
            val m = Matrix()

            m.postTranslate(-outW / 2f, -outH / 2f)
            m.postRotate(rot.toFloat())
            if (rot == 90 || rot == 270)
                m.postTranslate(outH / 2f, outW / 2f)
            else
                m.postTranslate(outW / 2f, outH / 2f)

            m.mapRect(r)

            r.left = r.left * scale + dx
            r.right = r.right * scale + dx
            r.top = r.top * scale + dy
            r.bottom = r.bottom * scale + dy


            boxes.add(r)
        }

        invalidate()
    }

}
