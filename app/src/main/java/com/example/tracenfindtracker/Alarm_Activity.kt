package com.example.tracenfindtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log // ✅ Added for debugging
import android.widget.Button
import androidx.activity.ComponentActivity

class AlarmActivity : ComponentActivity() {

    // Receiver to close this screen if the alarm stops remotely (via Web Dashboard)
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "CLOSE_ALARM_SCREEN") {
                Log.d("AlarmActivity", "Received close command. Finishing.")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Make it show over Lock Screen (Important!)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_alarm)

        val btnStop = findViewById<Button>(R.id.btnStopAlarm)

        // 2. Handle "STOP ALARM" Button Click
        btnStop.setOnClickListener {
            Log.d("AlarmActivity", "Stop button clicked. Sending signal...")

            val intent = Intent(LocationService.ACTION_STOP_ALARM)

            // ✅ CRITICAL FIX: Explicitly target OUR OWN app.
            // Without this, Android ignores the broadcast for security reasons.
            intent.setPackage(packageName)

            sendBroadcast(intent)

            // Close this screen immediately
            finish()
        }

        // Register receiver to close screen automatically if stopped from web
        val filter = IntentFilter("CLOSE_ALARM_SCREEN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(closeReceiver) } catch (e: Exception) {}
    }
}