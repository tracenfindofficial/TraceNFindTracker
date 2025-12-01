package com.example.tracenfindtracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

// --- 🎨 THEME & COLORS ---
val TnfPrimary = Color(0xFF4361EE)
val TnfBackground = Color(0xFF0F172A)
val TnfSurface = Color(0xFF1E293B)
val TnfTextWhite = Color(0xFFFFFFFF)
val TnfTextSlate = Color(0xFF94A3B8)
val TnfGreen = Color(0xFF10B981)
val TnfRed = Color(0xFFEF4444)

private val TraceNFindScheme = lightColorScheme(
    primary = TnfPrimary,
    onPrimary = Color.White,
    background = TnfBackground,
    onBackground = TnfTextWhite,
    surface = TnfSurface,
    onSurface = TnfTextWhite,
    outline = TnfTextWhite.copy(alpha = 0.5f)
)

class MainActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var userUid by mutableStateOf<String?>(null)
    private var hasAllPermissions by mutableStateOf(false)

    // MFA States
    private var showMfaDialog by mutableStateOf(false)
    private var mfaVerificationId by mutableStateOf("")
    private var mfaResolver: MultiFactorResolver? = null
    private var isEnrollingMode by mutableStateOf(false)

    companion object {
        private const val SHARED_PREFS_NAME = "tracker_prefs"
        private const val KEY_UID = "user_uid"
        private const val KEY_TRACKING_ACTIVE = "is_tracking_active"
    }

    // --- PERMISSION LAUNCHERS ---

    // ✅ Launcher for Overlay Permission (Display over other apps)
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // When they come back from settings, check if we can proceed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            checkPermissionsAndStartFlow()
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBackgroundLocation()
        } else {
            Toast.makeText(this, "Standard permissions denied.", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        checkLocationSettingsAndStart()
    }

    private val locationSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            hasAllPermissions = true
            userUid?.let { startTracking(it) }
        } else {
            Toast.makeText(this, "GPS required.", Toast.LENGTH_SHORT).show()
        }
    }

    private val googleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, gso)
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) { Log.w("MainActivity", "Google sign in failed", e) }
        }
    }

    private fun launchGoogleSignIn() { googleSignInLauncher.launch(googleSignInClient.signInIntent) }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    onSignInSuccess(task.result?.user?.uid!!)
                } else {
                    val exception = task.exception
                    if (exception is FirebaseAuthMultiFactorException) {
                        mfaResolver = exception.resolver
                        sendMfaSignInCode()
                    } else {
                        Toast.makeText(this, "Auth Failed: ${exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun startMfaEnrollment(phoneNumber: String) {
        val user = auth.currentUser ?: return
        isEnrollingMode = true

        user.multiFactor.session.addOnSuccessListener { session ->
            val options = PhoneAuthOptions.newBuilder()
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setMultiFactorSession(session)
                .setCallbacks(mfaCallbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
            showMfaDialog = true
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to start MFA: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMfaSignInCode() {
        val resolver = mfaResolver ?: return
        isEnrollingMode = false
        val phoneHint = resolver.hints.firstOrNull() as? PhoneMultiFactorInfo ?: return

        val options = PhoneAuthOptions.newBuilder()
            .setMultiFactorHint(phoneHint)
            .setMultiFactorSession(resolver.session)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(mfaCallbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
        showMfaDialog = true
    }

    private val mfaCallbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) { finalizeMfa(credential.smsCode!!) }
        override fun onVerificationFailed(e: FirebaseException) {
            Toast.makeText(this@MainActivity, "Verification Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            showMfaDialog = false
        }
        override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) { mfaVerificationId = verificationId }
    }

    private fun finalizeMfa(smsCode: String) {
        val credential = PhoneAuthProvider.getCredential(mfaVerificationId, smsCode)
        if (isEnrollingMode) {
            val user = auth.currentUser ?: return
            val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
            user.multiFactor.enroll(assertion, "SMS Protection")
                .addOnSuccessListener {
                    Toast.makeText(this, "2FA Enabled Successfully!", Toast.LENGTH_SHORT).show()
                    showMfaDialog = false
                }
                .addOnFailureListener { Toast.makeText(this, "Enrollment Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
        } else {
            val resolver = mfaResolver ?: return
            val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
            resolver.resolveSignIn(assertion)
                .addOnSuccessListener { result ->
                    onSignInSuccess(result.user!!.uid)
                    showMfaDialog = false
                }
                .addOnFailureListener { Toast.makeText(this, "Invalid Code", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun signOut() {
        auth.signOut()
        googleSignInClient.signOut()
        getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_UID).putBoolean(KEY_TRACKING_ACTIVE, false).apply()
        stopTracking()
        userUid = null
    }

    private fun onSignInSuccess(uid: String) {
        userUid = uid
        saveUserUid(uid)
        hasAllPermissions = areAllPermissionsGranted()
    }

    // ✅ Re-check permissions every time app resumes
    override fun onResume() {
        super.onResume()
        if (userUid != null) {
            hasAllPermissions = areAllPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        userUid = retrieveUserUid()
        hasAllPermissions = areAllPermissionsGranted()

        setContent {
            MaterialTheme(colorScheme = TraceNFindScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (userUid == null) {
                        LandingLoginScreen(
                            onGoogleClick = { launchGoogleSignIn() },
                            onEmailLogin = { email, pass ->
                                auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
                                    if (task.isSuccessful) onSignInSuccess(task.result?.user?.uid!!)
                                    else if (task.exception is FirebaseAuthMultiFactorException) {
                                        mfaResolver = (task.exception as FirebaseAuthMultiFactorException).resolver
                                        sendMfaSignInCode()
                                    } else Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRegister = { email, pass ->
                                auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
                                    if (task.isSuccessful) onSignInSuccess(task.result?.user?.uid!!)
                                    else Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        if (!hasAllPermissions) {
                            PermissionRequestScreen(onGrantClick = { checkPermissionsAndStartFlow() })
                        } else {
                            DashboardScreen(
                                userUid = userUid!!,
                                onStartTracking = { checkPermissionsAndStartFlow() },
                                onStopTracking = ::stopTracking,
                                onSignOut = ::signOut,
                                onEnableMfa = ::startMfaEnrollment
                            )
                        }
                    }
                    if (showMfaDialog) {
                        MfaDialog(isEnrollment = isEnrollingMode, onSubmit = { code -> finalizeMfa(code) }, onDismiss = { showMfaDialog = false })
                    }
                }
            }
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return false

        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissionsAndStartFlow() {
        // ✅ Check Overlay Permission FIRST.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please allow 'Display over other apps' to enable lock screen security.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
            return // Stop here, wait for them to come back
        }

        // Standard Permissions
        val standardPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) standardPermissions.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = standardPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        checkLocationSettingsAndStart()
    }

    private fun checkLocationSettingsAndStart() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { hasAllPermissions = true; userUid?.let { startTracking(it) } }
            .addOnFailureListener { e -> if (e is ResolvableApiException) { try { locationSettingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build()) } catch (_: Exception) {} } }
    }

    private fun saveUserUid(uid: String) { getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_UID, uid).apply() }
    private fun retrieveUserUid(): String? { return getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_UID, null) }

    private fun startTracking(uid: String) {
        getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRACKING_ACTIVE, true).apply()
        // Update status to online
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "defaultDevice"
        db.collection("user_data").document(uid).collection("devices").document(deviceId).update("status", "online")
            .addOnFailureListener { Log.e("Status", "Failed to set online: $it") }

        val intent = Intent(this, LocationService::class.java).apply { putExtra(LocationService.EXTRA_USER_UID, uid) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopTracking() {
        getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRACKING_ACTIVE, false).apply()
        // Update status to offline
        userUid?.let { uid ->
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "defaultDevice"
            db.collection("user_data").document(uid).collection("devices").document(deviceId).update("status", "offline")
                .addOnFailureListener { Log.e("Status", "Failed to set offline: $it") }
        }

        stopService(Intent(this, LocationService::class.java))
    }
}

// --- UI COMPONENTS ---

@Composable
fun MfaDialog(isEnrollment: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEnrollment) "Setup 2FA" else "Enter SMS Code", color = Color.White) },
        text = {
            Column {
                if (isEnrollment) {
                    Text("Enter phone number (+60...):", color = TnfTextSlate)
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") })
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(if (isEnrollment) "Then enter the code below:" else "A code has been sent to your phone.", color = TnfTextSlate)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") })
            }
        },
        confirmButton = {
            Button(onClick = { if (isEnrollment && code.isEmpty() && phone.isNotEmpty()) onSubmit(phone) else onSubmit(code) }, colors = ButtonDefaults.buttonColors(containerColor = TnfPrimary)) {
                Text(if (isEnrollment && code.isEmpty()) "Get Code" else "Verify")
            }
        },
        containerColor = TnfSurface
    )
}

@Composable
fun PermissionRequestScreen(onGrantClick: () -> Unit) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().background(TnfBackground).padding(32.dp).verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Security, null, tint = TnfPrimary, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Permissions Required", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Trace'N Find needs access to secure your device:", color = TnfTextSlate, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))

        PermissionItem(Icons.Filled.LocationOn, "Location", "Track device movement")
        PermissionItem(Icons.Filled.CameraAlt, "Camera", "Capture intruder photos")
        PermissionItem(Icons.Filled.SimCard, "Phone & SMS", "Detect SIM swap")
        PermissionItem(Icons.Filled.Contacts, "Contacts", "Backup data")
        // Added Explicit UI for clarity
        PermissionItem(Icons.Filled.Layers, "Overlay", "Show security screen")

        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onGrantClick, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = TnfPrimary), shape = RoundedCornerShape(12.dp)) {
            Text("GRANT ALL PERMISSIONS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun PermissionItem(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(icon, null, tint = TnfGreen, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(desc, color = TnfTextSlate, fontSize = 12.sp)
        }
    }
}

@Composable
fun DashboardScreen(userUid: String, onStartTracking: () -> Unit, onStopTracking: () -> Unit, onSignOut: () -> Unit, onEnableMfa: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE) }
    var isTracking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val running = activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == LocationService::class.java.name
        }
        isTracking = running
    }

    var customPin by remember { mutableStateOf(prefs.getString("sms_pin", "#1234") ?: "#1234") }
    var showMfaInput by remember { mutableStateOf(false) }
    var phoneInput by remember { mutableStateOf("") }

    if (showMfaInput) {
        AlertDialog(
            onDismissRequest = { showMfaInput = false },
            title = { Text("Enable 2FA", color = Color.White) },
            text = { OutlinedTextField(value = phoneInput, onValueChange = { phoneInput = it }, label = { Text("+60...") }, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) },
            confirmButton = { Button(onClick = { onEnableMfa(phoneInput); showMfaInput = false }) { Text("Send Code") } },
            containerColor = TnfSurface
        )
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().background(TnfSurface).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Correct logo resource
                    Image(painter = painterResource(id = R.drawable.tracenfind_logo), contentDescription = "Logo", modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trace'N Find", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Row {
                    // ❌ LOCK BUTTON REMOVED HERE
                    IconButton(onClick = onSignOut) { Icon(Icons.Outlined.Logout, contentDescription = "Logout", tint = TnfRed) }
                }
            }
        },
        containerColor = TnfBackground
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp).verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TnfSurface), border = BorderStroke(1.dp, if (isTracking) TnfGreen.copy(alpha = 0.5f) else TnfRed.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(if (isTracking) TnfGreen.copy(alpha = 0.2f) else TnfRed.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isTracking) Icons.Filled.Radar else Icons.Filled.LocationOff,
                            contentDescription = null,
                            tint = if (isTracking) TnfGreen else TnfRed,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(if (isTracking) "Monitoring Active" else "Protection Disabled", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isTracking) "Your device is secure." else "Start the service to enable protection.", color = TnfTextSlate, textAlign = TextAlign.Center, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoCard(title = "Device ID", value = Build.MODEL, icon = Icons.Filled.PhoneAndroid, modifier = Modifier.weight(1f))
                InfoCard(title = "User", value = userUid.take(4) + "...", icon = Icons.Filled.Person, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TnfSurface.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SMS Security Code", color = TnfTextSlate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = customPin, onValueChange = { customPin = it; prefs.edit().putString("sms_pin", it).apply() }, label = { Text("Secret PIN") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { if (isTracking) { onStopTracking(); isTracking = false } else { onStartTracking(); isTracking = true } }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isTracking) TnfRed else TnfPrimary), shape = RoundedCornerShape(12.dp)) {
                Text(if (isTracking) "STOP TRACKING" else "START LINK", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun InfoCard(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = TnfSurface.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.Start) {
            Icon(icon, null, tint = TnfTextSlate, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, color = TnfTextSlate, fontSize = 12.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandingLoginScreen(onGoogleClick: () -> Unit, onEmailLogin: (String, String) -> Unit, onRegister: (String, String) -> Unit) {
    var isRegistering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val themedTextFieldColors = TextFieldDefaults.colors(focusedContainerColor = TnfSurface, unfocusedContainerColor = TnfSurface, focusedIndicatorColor = TnfPrimary, unfocusedIndicatorColor = TnfTextSlate.copy(alpha = 0.5f), focusedTextColor = TnfTextWhite, unfocusedTextColor = TnfTextWhite, focusedLabelColor = TnfPrimary, unfocusedLabelColor = TnfTextSlate, cursorColor = TnfPrimary)
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize().background(TnfBackground)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(id = R.drawable.tracenfind_logo), contentDescription = "Logo", modifier = Modifier.size(80.dp), contentScale = ContentScale.Fit)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Trace'N Find", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TnfTextWhite)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Secure your devices instantly.", color = TnfTextSlate, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(48.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TnfSurface.copy(alpha = 0.5f)), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, TnfTextSlate.copy(alpha = 0.2f))) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email Address") }, modifier = Modifier.fillMaxWidth(), colors = themedTextFieldColors, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), colors = themedTextFieldColors, shape = RoundedCornerShape(12.dp), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null, tint = TnfTextSlate) } })
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { if (email.isNotEmpty() && password.isNotEmpty()) { if (isRegistering) onRegister(email, password) else onEmailLogin(email, password) } }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = TnfPrimary), shape = RoundedCornerShape(12.dp)) { Text(if (isRegistering) "Sign Up" else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onGoogleClick, modifier = Modifier.fillMaxWidth().height(50.dp), border = BorderStroke(1.dp, TnfTextSlate.copy(alpha = 0.5f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = TnfTextWhite), shape = RoundedCornerShape(12.dp)) { Text("Sign in with Google") }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { isRegistering = !isRegistering }) { Text(text = if (isRegistering) "Already have an account? Login" else "Don't have an account? Sign Up", color = TnfTextWhite) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeatureCarousel(modifier: Modifier = Modifier) {
    val webFeatures = listOf("Real-Time Tracking", "Remote Security", "Smart Alerts")
    val pagerState = rememberPagerState(pageCount = { webFeatures.size })
    LaunchedEffect(Unit) {
        while (true) { delay(3000); val nextPage = (pagerState.currentPage + 1) % webFeatures.size; pagerState.animateScrollToPage(nextPage) }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val feature = webFeatures[page]
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Star, null, tint = TnfPrimary, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(feature, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Secure your devices instantly.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.height(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(webFeatures.size) { iteration ->
                val color = if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.4f)
                Box(modifier = Modifier.padding(4.dp).clip(CircleShape).background(color).size(8.dp))
            }
        }
    }
}