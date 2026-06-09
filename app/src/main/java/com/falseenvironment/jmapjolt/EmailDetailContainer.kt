package com.falseenvironment.jmapjolt

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

internal class EmailDetailContainer(context: Context) : FrameLayout(context) {

    var onHorizontalSwipe: ((forward: Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val interceptThreshold = 18 * dp
    private val completeThreshold = 60 * dp

    private var startX = 0f
    private var startY = 0f
    private var intercepting = false
    // Only react to swipes that start in the header zone (set by caller after layout).
    var topZoneHeight = 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting && startY <= topZoneHeight) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    if (dx > interceptThreshold && dx > dy * 1.5f) {
                        intercepting = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
            }
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                if (abs(dx) >= completeThreshold) {
                    onHorizontalSwipe?.invoke(dx < 0)
                }
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return true
    }
}
