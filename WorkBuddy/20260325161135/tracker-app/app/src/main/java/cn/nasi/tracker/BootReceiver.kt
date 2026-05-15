package cn.nasi.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(
                Intent(context, TrackerService::class.java).apply {
                    action = TrackerService.ACTION_START
                }
            )
        }
    }
}
