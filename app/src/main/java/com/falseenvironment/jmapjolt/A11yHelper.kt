package com.falseenvironment.jmapjolt

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

// ---------------------------------------------------------------------------
// Reduced motion
// ---------------------------------------------------------------------------

// System animation scale, 0f when the user has turned animations off in Developer
// options or Accessibility. Screen transitions multiply their durations by this, so
// "remove animations" actually removes them instead of only shortening system ones.
internal fun Context.motionScale(): Float =
    try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (e: Exception) {
        1f
    }

// [duration] adjusted for the system animation scale; 0 means "apply the end state now".
internal fun View.scaledDuration(duration: Long): Long =
    (duration * context.motionScale()).toLong()

// ---------------------------------------------------------------------------
// Haptics
// ---------------------------------------------------------------------------

// Short confirmation tick for an action that changed something (swipe committed,
// message sent, selection entered). Silently does nothing when the device or the
// user's settings suppress haptics.
internal fun View.hapticConfirm() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

// Heavier tick for destructive or long-press-initiated actions.
internal fun View.hapticHeavy() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
