package com.falseenvironment.jmapjolt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated email-themed hero for the onboarding welcome page.
 * Draws a gently floating envelope (the launcher foreground) surrounded by
 * expanding ripple rings, orbiting accent dots and small paper planes that
 * fly off the envelope and fade out. All motion is driven by a single
 * looping ValueAnimator and uses only canvas transforms (no layout passes).
 */
internal class OnboardingHeroView(
    context: Context,
    private val accentColor: Int,
    private val subColor: Int
) : View(context) {

    private var t = 0f  // global time in [0,1), loops every CYCLE_MS

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private val logo: Bitmap =
        BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_foreground)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val planePath = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f
        val dp = resources.displayMetrics.density

        // --- Ripple rings: three staggered expanding, fading circles ---
        for (i in 0 until 3) {
            val phase = ((t + i / 3f) % 1f)
            val radius = base * (0.42f + 0.55f * phase)
            ringPaint.alpha = ((1f - phase) * 70).toInt()
            ringPaint.strokeWidth = (1.5f + (1f - phase) * 1.5f) * dp
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // --- Orbiting dots: three dots on slightly elliptical orbits ---
        for (i in 0 until 3) {
            val angle = (t * 2.0 * Math.PI) + i * (2.0 * Math.PI / 3.0)
            val rx = base * 0.62f
            val ry = base * 0.52f
            val dx = cx + (rx * cos(angle)).toFloat()
            val dy = cy + (ry * sin(angle)).toFloat()
            // dots shrink/grow subtly out of phase
            val pulse = 0.75f + 0.25f * sin(t * 2f * Math.PI.toFloat() * 2f + i * 2.1f)
            dotPaint.alpha = 200
            canvas.drawCircle(dx, dy, 3.2f * dp * pulse, dotPaint)
        }

        // --- Paper planes: two tiny planes flying up-right, looping ---
        for (i in 0 until 2) {
            val phase = ((t * 1.0f + i * 0.5f) % 1f)
            // ease-out along the trajectory
            val p = 1f - (1f - phase) * (1f - phase)
            val px = cx + base * (0.15f + 0.85f * p) * (if (i == 0) 1f else -1f)
            val py = cy - base * (0.30f + 0.75f * p)
            val alpha = ((1f - p) * 160).toInt()
            planePaint.color = if (i == 0) accentColor else subColor
            planePaint.alpha = alpha
            drawPlane(canvas, px, py, 7f * dp, mirrored = i != 0)
        }

        // --- Envelope logo: gentle vertical bob + soft tilt ---
        val bob = sin(t * 2f * Math.PI.toFloat()) * 7f * dp
        val tilt = sin(t * 2f * Math.PI.toFloat() + 1.3f) * 2.5f
        val logoSize = base * 1.05f
        canvas.save()
        canvas.translate(cx, cy + bob)
        canvas.rotate(tilt)
        val half = logoSize / 2f
        canvas.drawBitmap(
            logo, null,
            android.graphics.RectF(-half, -half, half, half),
            bitmapPaint
        )
        canvas.restore()
    }

    /** Tiny stylized paper plane pointing up-right (or up-left when mirrored). */
    private fun drawPlane(canvas: Canvas, x: Float, y: Float, size: Float, mirrored: Boolean) {
        val dir = if (mirrored) -1f else 1f
        planePath.reset()
        planePath.moveTo(x + size * dir, y - size)            // nose
        planePath.lineTo(x - size * dir, y - size * 0.1f)     // left wing tip
        planePath.lineTo(x - size * 0.15f * dir, y + size * 0.15f) // body notch
        planePath.lineTo(x - size * 0.35f * dir, y + size)    // tail
        planePath.close()
        canvas.drawPath(planePath, planePaint)
    }

    companion object {
        private const val CYCLE_MS = 4000L
    }
}
