package com.shadowphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Best-effort restart after boot; Android may still defer or block background service starts. */
internal class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MeshKeepAliveService.start(context)
    }
}
