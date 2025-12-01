package com.example.tracenfindtracker

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class LostModeActivity : ComponentActivity() {
    private lateinit var deviceId: String
    private lateinit var userUid: String

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "tracenfind") }
    private var statusListener: ListenerRegistration? = null

    // Receiver to catch the "Found" signal from LocationService
    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_DEVICE_FOUND) {
                unlockAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Show over Lock Screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // 2. Hide System Bars (Immersive Mode)
        hideSystemUI()

        // 3. ✅ BLOCK BACK BUTTON (Kept as requested)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing. Back button is disabled.
            }
        })

        setContentView(R.layout.activity_lost_mode)

        deviceId = intent.getStringExtra("deviceId") ?: "defaultDevice"
        userUid = intent.getStringExtra("userUid") ?: ""

        if (userUid.isEmpty()) {
            val prefs = getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)
            userUid = prefs.getString("user_uid", "") ?: ""
            if (userUid.isEmpty()) { finish(); return }
        }

        val editTextMessage = findViewById<EditText>(R.id.editTextMessage)
        val btnSendMessage = findViewById<Button>(R.id.btnSendMessage)

        btnSendMessage.setOnClickListener {
            val finderMessage = editTextMessage.text.toString()
            val cameraIntent = Intent(this, CameraActivity::class.java).apply {
                putExtra("deviceId", deviceId)
                putExtra("userUid", userUid)
                putExtra("message", finderMessage)
            }
            startActivity(cameraIntent)
        }

        // Start watching DB to unlock automatically
        startLiveStatusCheck()

        val filter = IntentFilter(LocationService.ACTION_DEVICE_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(foundReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(foundReceiver, filter)
        }
    }

    private fun startLiveStatusCheck() {
        if (userUid.isEmpty()) return

        val docRef = db.collection("user_data").document(userUid)
            .collection("devices").document(deviceId)

        statusListener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener

            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.getString("status")
                if (status != "lost") {
                    unlockAndFinish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        // ❌ REMOVED: startLockTask() (No more pinning/home blocking)
    }

    private fun unlockAndFinish() {
        // ❌ REMOVED: stopLockTask()

        val mainActivityIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(mainActivityIntent)
        finishAndRemoveTask()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val intent = Intent(LocationService.ACTION_STOP_ALARM)
            sendBroadcast(intent)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusListener?.remove()
        try { unregisterReceiver(foundReceiver) } catch(e:Exception){}
    }
}