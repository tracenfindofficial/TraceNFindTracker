package com.example.tracenfindtracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaActionSound // ✅ Added for Sound
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect // ✅ Added for Vibration
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var loadingOverlay: LinearLayout // ✅ New UI
    private lateinit var tvStatus: TextView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "tracenfind") }

    private lateinit var deviceId: String
    private lateinit var userUid: String
    private var message: String = ""

    // Sound Player
    private val shutterSound = MediaActionSound() // ✅ Standard Camera Sound

    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_DEVICE_FOUND) {
                val mainActivityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(mainActivityIntent)
                finish()
            }
        }
    }

    private fun getDeviceAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "defaultDevice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        loadingOverlay = findViewById(R.id.loadingOverlay) // ✅ Init
        tvStatus = findViewById(R.id.tvStatus)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Pre-load shutter sound
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        deviceId = intent.getStringExtra("deviceId") ?: getDeviceAndroidId()
        userUid = intent.getStringExtra("userUid") ?: ""
        message = intent.getStringExtra("message") ?: ""

        if (userUid.isEmpty()) { finish(); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        startCamera()

        btnCapture.setOnClickListener {
            takePhoto()
        }

        val filter = IntentFilter(LocationService.ACTION_DEVICE_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(foundReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(foundReceiver, filter)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (t: Throwable) { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // ✅ 1. INSTANT FEEDBACK (Sound, Vibration, Visuals)
        shutterSound.play(MediaActionSound.SHUTTER_CLICK) // CLICK!
        vibratePhone() // BZZT!

        // Disable button and show loading screen
        btnCapture.isEnabled = false
        loadingOverlay.visibility = View.VISIBLE
        tvStatus.text = "Capturing Evidence..."

        val photoFile = File.createTempFile("selfie_", ".jpg", cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    resetUI()
                    Toast.makeText(this@CameraActivity, "Capture Failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    tvStatus.text = "Uploading Securely..." // Update text
                    val bytes = photoFile.readBytes()
                    uploadPhotoToFirebase(bytes, message)
                }
            }
        )
    }

    private fun uploadPhotoToFirebase(bytes: ByteArray, message: String) {
        val timestamp = System.currentTimeMillis()
        val storagePath = "finder_uploads/$userUid/$deviceId/$timestamp.jpg"
        val storageRef = storage.reference.child(storagePath)

        storageRef.putBytes(bytes)
            .addOnSuccessListener {
                tvStatus.text = "Saving Log..."
                storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    saveInfoToFirestore(downloadUrl.toString(), message)
                }
            }
            .addOnFailureListener {
                handleUploadError(it)
            }
    }

    private fun saveInfoToFirestore(photoUrl: String, message: String) {
        val deviceDocRef = db.collection("user_data")
            .document(userUid)
            .collection("devices")
            .document(deviceId)

        // 1. Add to Log History (Keep this for history)
        val finderData = hashMapOf(
            "message" to message,
            "photo_url" to photoUrl,
            "timestamp" to FieldValue.serverTimestamp()
        )
        deviceDocRef.collection("evidence_logs").add(finderData)

        // 2. Update Latest Status on Parent Document (Triggers Web Notification)
        val updates = hashMapOf<String, Any>(
            "finder_message" to message,
            "finder_photo_url" to photoUrl,
            "finder_data_timestamp" to FieldValue.serverTimestamp()
        )

        deviceDocRef.update(updates)
            .addOnSuccessListener {
                tvStatus.text = "Done!"
                Toast.makeText(this@CameraActivity, "Evidence Sent.", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener {
                handleUploadError(it)
            }
    }

    private fun handleUploadError(exception: Exception) {
        runOnUiThread {
            resetUI()
            Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetUI() {
        loadingOverlay.visibility = View.GONE
        btnCapture.isEnabled = true
    }

    // ✅ VIBRATION HELPER
    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release() // Release sound resource
        cameraExecutor.shutdown()
        unregisterReceiver(foundReceiver)
    }
}