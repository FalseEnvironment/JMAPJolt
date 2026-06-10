package com.falseenvironment.jmapjolt

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.FrameLayout
import kotlin.math.abs

internal class EmailDetailContainer(context: Context) : FrameLayout(context) {

    /** Live finger-follow updates while a header swipe is in progress (dx from drag start). */
    var onSwipeDrag: ((dx: Float) -> Unit)? = null

    /** Finger lifted or gesture cancelled: total dx and horizontal velocity in px/s. */
    var onSwipeEnd: ((dx: Float, velocityX: Float) -> Unit)? = null

    // Only react to swipes that start in the header zone (set by caller after layout).
    var topZoneHeight = 0

    private val dp = resources.displayMetrics.density
    private val interceptThreshold = 18 * dp

    private var startX = 0f
    private var startY = 0f
    private var dragOriginX = 0f
    private var intercepting = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                intercepting = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!intercepting && startY <= topZoneHeight) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    if (dx > interceptThreshold && dx > dy * 1.5f) {
                        intercepting = true
                        // Drag tracks from here so the content doesn't jump by the threshold.
                        dragOriginX = ev.x
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
                recycleTracker()
            }
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touch landed directly on the container (no child consumed it):
                // intercept won't be called again, so track the gesture from here.
                startX = ev.x
                startY = ev.y
                intercepting = false
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting && startY <= topZoneHeight) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    if (dx > interceptThreshold && dx > dy * 1.5f) {
                        intercepting = true
                        dragOriginX = ev.x
                    }
                }
                if (intercepting) onSwipeDrag?.invoke(ev.x - dragOriginX)
            }
            MotionEvent.ACTION_UP -> {
                val vx = velocityTracker?.let {
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                if (intercepting) onSwipeEnd?.invoke(ev.x - dragOriginX, vx)
                intercepting = false
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (intercepting) onSwipeEnd?.invoke(0f, 0f)
                intercepting = false
                recycleTracker()
            }
        }
        return true
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
