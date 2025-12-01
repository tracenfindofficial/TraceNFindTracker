package com.example.tracenfindtracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "tracenfind") }

    private var userUid: String? = null
    private var deviceRef: DocumentReference? = null
    private var deviceListener: ListenerRegistration? = null
    private var isLostModeActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var isRinging = false
    private var lastHistoryLocation: Location? = null

    private var geofenceLat: Double? = null
    private var geofenceLng: Double? = null
    private var geofenceRadius: Float? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
        const val NOTIFICATION_ID = 101
        const val EXTRA_USER_UID = "user_uid"
        const val ACTION_DEVICE_FOUND = "com.example.tracenfindtracker.ACTION_DEVICE_FOUND"
        const val ACTION_STOP_ALARM = "com.example.tracenfindtracker.ACTION_STOP_ALARM"
    }

    private val stopAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_ALARM) {
                stopAlarm()
                updateDatabaseAction("stop")
            }
        }
    }

    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.intent.action.SIM_STATE_CHANGED") {
                Log.d("LocationService", "SIM State Changed detected")
                updateSecurityStatusOnly()
            }
        }
    }

    private fun getDeviceAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "defaultDevice"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        val filter = IntentFilter(ACTION_STOP_ALARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopAlarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopAlarmReceiver, filter)
        }

        val simFilter = IntentFilter("android.intent.action.SIM_STATE_CHANGED")
        registerReceiver(simStateReceiver, simFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var incomingUid = intent?.getStringExtra(EXTRA_USER_UID)
        val prefs = getSharedPreferences("trace_n_find_service_prefs", Context.MODE_PRIVATE)

        if (incomingUid != null) {
            prefs.edit().putString(EXTRA_USER_UID, incomingUid).apply()
            userUid = incomingUid
        } else {
            userUid = prefs.getString(EXTRA_USER_UID, null)
        }

        if (userUid.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ ONLINE STATUS UPDATE
        if (userUid != null) {
            val deviceId = getDeviceAndroidId()
            db.collection("user_data")
                .document(userUid!!)
                .collection("devices")
                .document(deviceId)
                .update("status", "online")
                .addOnFailureListener { e -> Log.e("LocationService", "Failed to set online", e) }
        }

        removeLocationUpdates()
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        startListeningForStatusChanges()
        return START_STICKY
    }

    private fun updateSecurityStatusOnly() {
        val uid = userUid ?: return
        val deviceId = getDeviceAndroidId()
        val deviceRef = db.collection("user_data").document(uid).collection("devices").document(deviceId)
        val simInfo = checkSimSecurity()
        deviceRef.update(
            mapOf(
                "security.sim_status" to simInfo["status"],
                "security.carrier_name" to simInfo["carrier"],
                "lastSeen" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener { Log.e("LocationService", "Failed to update security: $it") }
    }

    // --- 📊 NEW HELPER FUNCTIONS START HERE ---

    private fun getStorageInfo(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val bytesTotal = stat.blockSizeLong * stat.blockCountLong

            val gbAvailable = bytesAvailable / (1024f * 1024f * 1024f)
            val gbTotal = bytesTotal / (1024f * 1024f * 1024f)

            "%.1f GB / %.1f GB".format(gbAvailable, gbTotal)
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getSignalStrength(): String {
        var strength = "N/A"
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val cellInfos = tm.allCellInfo
                if (!cellInfos.isNullOrEmpty()) {
                    // Grab the first registered cell tower info or the first available
                    val currentCell = cellInfos.firstOrNull { it.isRegistered } ?: cellInfos[0]

                    val level = when (currentCell) {
                        is android.telephony.CellInfoLte -> currentCell.cellSignalStrength.level
                        is android.telephony.CellInfoGsm -> currentCell.cellSignalStrength.level
                        is android.telephony.CellInfoWcdma -> currentCell.cellSignalStrength.level
                        is android.telephony.CellInfoNr -> currentCell.cellSignalStrength.level // 5G
                        else -> 0
                    }

                    // Convert 0-4 scale to text
                    strength = when (level) {
                        4 -> "Excellent"
                        3 -> "Good"
                        2 -> "Fair"
                        1 -> "Poor"
                        else -> "Weak"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Signal Error", e)
        }
        return strength
    }

    private fun getNetworkInfo(): Map<String, String> {
        var ssid = "Unknown"
        var ip = "0.0.0.0"
        var type = "Offline"
        var mac = "Unknown"
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    type = "Wi-Fi"
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val connectionInfo = wm.connectionInfo
                    if (connectionInfo != null) {
                        ssid = connectionInfo.ssid.replace("\"", "")
                        ip = Formatter.formatIpAddress(connectionInfo.ipAddress)
                        mac = connectionInfo.bssid ?: "Unknown"
                    }
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    type = "Mobile Data"
                    // IP for mobile data is harder to get reliably without iterating interfaces,
                    // but usually not critical for this use case.
                }
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Network Info Error", e)
        }
        return mapOf("type" to type, "ssid" to ssid, "ip" to ip, "mac" to mac)
    }

    // --- 📊 NEW HELPER FUNCTIONS END HERE ---

    private fun checkSimSecurity(): Map<String, String> {
        var simStatus = "Checking..."
        var carrier = "Unknown"
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val state = tm.simState
            when (state) {
                TelephonyManager.SIM_STATE_ABSENT -> simStatus = "🚨 SIM REMOVED"
                TelephonyManager.SIM_STATE_READY -> {
                    carrier = tm.simOperatorName ?: "Unknown"
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                        val subs = sm.activeSubscriptionInfoList
                        if (!subs.isNullOrEmpty()) {
                            val currentId = subs[0].subscriptionId.toString()
                            val prefs = getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
                            val savedId = prefs.getString("trusted_sim_id", null)
                            if (savedId == null) {
                                prefs.edit().putString("trusted_sim_id", currentId).apply()
                                simStatus = "Trusted SIM Set"
                            } else if (savedId != currentId) {
                                simStatus = "🚨 SIM SWAPPED"
                            } else {
                                simStatus = "Secure"
                            }
                        }
                    }
                }
                else -> simStatus = "SIM State: $state"
            }
        } catch (e: Exception) { simStatus = "Error" }
        return mapOf("status" to simStatus, "carrier" to carrier)
    }

    private fun performBackup() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val contacts = ArrayList<HashMap<String, String>>()
        val cursor = contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < 100) {
                contacts.add(hashMapOf(
                    "name" to (it.getString(it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "Unknown"),
                    "number" to (it.getString(it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: "")
                ))
                count++
            }
        }
        db.collection("user_data").document(userUid!!).collection("devices").document(getDeviceAndroidId())
            .collection("backups").document("contacts").set(hashMapOf("data" to contacts, "time" to FieldValue.serverTimestamp()))
            .addOnSuccessListener { updateDatabaseAction("backup_complete") }
    }

    private fun startListeningForStatusChanges() {
        val uid = userUid ?: return
        val deviceId = getDeviceAndroidId()
        deviceRef = db.collection("user_data").document(uid).collection("devices").document(deviceId)
        deviceListener?.remove()
        deviceListener = deviceRef?.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            geofenceLat = snapshot.getDouble("geofence_lat")
            geofenceLng = snapshot.getDouble("geofence_lng")
            geofenceRadius = snapshot.getDouble("geofence_radius")?.toFloat()

            val status = snapshot.getString("status")
            if (status == "lost") {
                if (!isLostModeActive) { isLostModeActive = true; triggerLostMode() }
            } else {
                if (isLostModeActive) { isLostModeActive = false; sendBroadcast(Intent(ACTION_DEVICE_FOUND)) }
            }

            val action = snapshot.getString("pending_action")
            when (action) {
                "ring" -> if (!isRinging) startAlarm()
                "backup" -> performBackup()
                else -> if (isRinging) stopAlarm()
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000).setMinUpdateDistanceMeters(0f).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let {
                    sendLocationToFirebase(it)
                    checkGeofence(it)
                }
            }
        }
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {}
    }

    // --- 🚀 MAIN UPDATE FUNCTION ---
    private fun sendLocationToFirebase(location: Location) {
        val uid = userUid ?: return
        val deviceId = getDeviceAndroidId()
        val deviceRef = db.collection("user_data").document(uid).collection("devices").document(deviceId)

        // 1. Get Battery
        val batMan = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batLvl = batMan.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // 2. Get Info Maps
        val netInfo = getNetworkInfo()
        val simInfo = checkSimSecurity()
        val storageInfo = getStorageInfo()
        val signalStrength = getSignalStrength()

        // 3. Extract Flat Values for Dashboard
        val ipAddress = netInfo["ip"] ?: "N/A"
        val macAddress = netInfo["mac"] ?: "N/A"
        val connectionType = netInfo["type"] ?: "Offline"
        val carrierName = simInfo["carrier"] ?: "Unknown"
        val ssid = netInfo["ssid"] ?: ""

        // Format: "Wi-Fi (MyHome)" or just "Mobile Data"
        val displayNetwork = if(ssid.isNotEmpty() && ssid != "Unknown") "$connectionType ($ssid)" else connectionType

        // 4. Construct Payload
        val currentPayload = hashMapOf(
            "name" to Build.MODEL,
            "model" to Build.MODEL,
            "type" to "Phone",
            "battery" to batLvl,

            // ✅ Fix for Operating System
            "os" to "Android",
            "os_version" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "operating_system" to "Android ${Build.VERSION.RELEASE}",

            "lastSeen" to FieldValue.serverTimestamp(),
            "location" to hashMapOf("lat" to location.latitude, "lng" to location.longitude),

            // Nested maps (keep for history/deep details)
            "network" to netInfo,
            "security" to simInfo,

            // ✅ Flat fields for Dashboard "N/A" Fixes
            "network_display" to displayNetwork,
            "ip_address" to ipAddress,
            "mac_address" to macAddress,
            "carrier" to carrierName,
            "storage" to storageInfo,
            "signal_strength" to signalStrength
        )

        deviceRef.set(currentPayload, SetOptions.merge())

        if (lastHistoryLocation == null || location.distanceTo(lastHistoryLocation!!) > 50) {
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val newPoint = hashMapOf("lat" to location.latitude, "lng" to location.longitude, "time" to Date())
            deviceRef.collection("location_history").document(todayDate)
                .set(hashMapOf("route" to FieldValue.arrayUnion(newPoint)), SetOptions.merge())
                .addOnSuccessListener { lastHistoryLocation = location }
        }
    }

    private fun checkGeofence(loc: Location) {
        if (geofenceLat != null && geofenceLng != null && geofenceRadius != null) {
            val center = Location("C").apply { latitude = geofenceLat!!; longitude = geofenceLng!! }
            val status = if (loc.distanceTo(center) > geofenceRadius!!) "OUTSIDE" else "INSIDE"
            db.collection("user_data").document(userUid!!).collection("devices").document(getDeviceAndroidId())
                .update("geofence_status", status)
        }
    }

    private fun startAlarm() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (isRinging && mediaPlayer?.isPlaying == true) return@post
                isRinging = true
                val alarmUri = Uri.parse("android.resource://$packageName/${R.raw.alarm_sound}")
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, alarmUri)
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    isLooping = true
                    setOnPreparedListener {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                        start()

                        val intent = Intent(this@LocationService, AlarmActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pIntentFull = PendingIntent.getActivity(this@LocationService, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        startActivity(intent)

                        val stopIntent = Intent(ACTION_STOP_ALARM).setPackage(packageName)
                        val pIntent = PendingIntent.getBroadcast(this@LocationService, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val notif = NotificationCompat.Builder(this@LocationService, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle("🚨 ALARM ACTIVE").setContentText("Tap to Stop").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setColor(Color.RED)
                            .setFullScreenIntent(pIntentFull, true)
                            .addAction(android.R.drawable.ic_media_pause, "STOP ALARM", pIntent).build()
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
                    }
                    prepareAsync()
                }
            } catch (e: Exception) { isRinging = false }
        }
    }

    private fun stopAlarm() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isRinging = false
            sendBroadcast(Intent("CLOSE_ALARM_SCREEN"))
            val notif = buildNotification()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {}
    }

    private fun updateDatabaseAction(action: String) {
        val uid = userUid ?: return
        val deviceId = getDeviceAndroidId()
        db.collection("user_data").document(uid).collection("devices").document(deviceId).update("pending_action", action)
    }

    private fun triggerLostMode() {
        try {
            val intent = Intent(this, LostModeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK; putExtra("deviceId", getDeviceAndroidId()); putExtra("userUid", userUid)
            }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun removeLocationUpdates() {
        if (::locationCallback.isInitialized) {
            try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Trace'N Find").setContentText("Monitoring...").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
    }

    override fun onDestroy() {
        super.onDestroy()

        // ✅ OFFLINE STATUS UPDATE
        if (userUid != null) {
            val deviceId = getDeviceAndroidId()
            db.collection("user_data")
                .document(userUid!!)
                .collection("devices")
                .document(deviceId)
                .update("status", "offline")
                .addOnFailureListener { e -> Log.e("LocationService", "Failed to set offline", e) }
        }

        stopAlarm()
        try { unregisterReceiver(stopAlarmReceiver) } catch(e:Exception){}
        try { unregisterReceiver(simStateReceiver) } catch(e:Exception){}
        removeLocationUpdates()
        deviceListener?.remove()
    }
}