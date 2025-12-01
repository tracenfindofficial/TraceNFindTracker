package com.example.tracenfindtracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class SmsCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>
                for (pdu in pdus) {
                    val format = bundle.getString("format")
                    val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)

                    val senderNumber = msg.originatingAddress ?: ""
                    val messageBody = msg.messageBody.trim()

                    // Get saved PIN (Default is #1234)
                    val prefs = context.getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)
                    val savedPin = prefs.getString("sms_pin", "#1234") ?: "#1234"

                    Log.d("SMS_DEBUG", "Checking: '$messageBody' against '$savedPin'")

                    // Check for LOCATE command
                    if (messageBody.startsWith("$savedPin LOCATE", ignoreCase = true)) {
                        Log.d("SMS_DEBUG", "✅ Match found! Starting tracking.")
                        abortBroadcast() // Stop SMS from appearing in inbox
                        fetchLocationAndReply(context, senderNumber)
                    }
                }
            }
        }
    }

    private fun fetchLocationAndReply(context: Context, senderNumber: String) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val battery = getBatteryLevel(context)

                    // ✅ FIX: Removed 'http://' to bypass carrier spam filters.
                    // Most phones will still recognize 'maps.google.com' as a link.
                    val mapsLink = "maps.google.com/?q=${location.latitude},${location.longitude}"

                    // Short, clean message
                    val response = "TraceNFind: Bat $battery% Loc: $mapsLink"

                    sendSMS(context, senderNumber, response)
                } else {
                    sendSMS(context, senderNumber, "TraceNFind: GPS on but no signal.")
                }
            }
        } catch (e: SecurityException) {
            Log.e("SMS_DEBUG", "Permission missing")
        }
    }

    private fun sendSMS(context: Context, phoneNumber: String, message: String) {
        try {
            var smsManager = SmsManager.getDefault()

            // Dual SIM Support: Find the active SIM
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeInfoList = subscriptionManager.activeSubscriptionInfoList

                if (activeInfoList != null && activeInfoList.isNotEmpty()) {
                    val subscriptionId = activeInfoList[0].subscriptionId
                    smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
                    } else {
                        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                    }
                    Log.d("SMS_DEBUG", "Sending via Active SIM (SubID: $subscriptionId)")
                }
            }

            // ✅ USE MULTIPART to prevent failure on long messages
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)

            Log.d("SMS_DEBUG", "Reply sent to $phoneNumber")

        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Failed to send SMS: ${e.message}")
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}