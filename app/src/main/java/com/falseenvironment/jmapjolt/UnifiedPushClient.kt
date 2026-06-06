package com.falseenvironment.jmapjolt

import android.content.Context
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

class UnifiedPushClient(private val context: Context) {

    fun register(callback: (String?) -> Unit) {
        try {
            UnifiedPush.registerApp(context, INSTANCE_DEFAULT, arrayListOf(), context.packageName)
            callback("registered")
        } catch (_: Throwable) {
            callback(null)
        }
    }
}