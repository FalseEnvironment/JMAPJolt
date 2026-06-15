package com.falseenvironment.jmapjolt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Discord-style avatar editor: pan, pinch-zoom and free rotation of a source
 * photo behind a circular crop window. [getCroppedBitmap] renders the area
 * inside the circle to a square bitmap.
 */
class AvatarCropView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var minScale = 1f
    private var posX = 0f
    private var posY = 0f

    /** Free rotation in degrees, driven by the slider in the dialog. */
    var rotationDeg = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val matrix = Matrix()
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(minScale, minScale * 8f)
                invalidate()
                return true
            }
        }
    )

    fun setBitmap(b: Bitmap) {
        bitmap = b
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) resetFit()
    }

    private fun cropDiameter(): Float = minOf(width, height) * 0.82f

    private fun resetFit() {
        val bmp = bitmap ?: return
        val d = cropDiameter()
        minScale = d / minOf(bmp.width, bmp.height).toFloat()
        scale = minScale
        rotationDeg = 0f
        posX = width / 2f
        posY = height / 2f
    }

    private fun buildMatrix(): Matrix {
        val bmp = bitmap ?: return matrix
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotationDeg)
        matrix.postTranslate(posX, posY)
        return matrix
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, buildMatrix(), null)

        val r = cropDiameter() / 2f
        val cx = width / 2f
        val cy = height / 2f
        val layer = canvas.saveLayer(null, null)
        canvas.drawColor(0xB0000000.toInt())
        canvas.drawCircle(cx, cy, r, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawCircle(cx, cy, r, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    posX += event.x - lastX
                    posY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    /** Render the circular crop window into a [size] x [size] square bitmap. */
    fun getCroppedBitmap(size: Int): Bitmap? {
        val bmp = bitmap ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val d = cropDiameter()
        val left = width / 2f - d / 2f
        val top = height / 2f - d / 2f
        val s = size / d
        canvas.scale(s, s)
        canvas.translate(-left, -top)
        canvas.drawBitmap(bmp, buildMatrix(), null)
        return out
    }
}
