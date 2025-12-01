package com.example.tracenfindtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("TraceNFind", "Phone restarted. Checking if tracking should resume...")

            // Check if tracking was active before restart
            val prefs = context.getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)
            val wasTracking = prefs.getBoolean("is_tracking_active", false)
            val userUid = prefs.getString("user_uid", null)

            if (wasTracking && userUid != null) {
                Log.d("TraceNFind", "Tracking was active. Restarting Service now.")

                // Restart the Service
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    putExtra("user_uid", userUid)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d("TraceNFind", "Tracking was OFF. Doing nothing.")
            }
        }
    }
}