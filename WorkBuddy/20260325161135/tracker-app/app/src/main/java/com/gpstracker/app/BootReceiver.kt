package com.gpstracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机自启动 MQTT 服务（常驻，收到指令后再启动 LocationService）
            MqttService.start(context)
        }
    }
}
