package com.speqta.staffpayroll

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.speqta.staffpayroll.ui.theme.StaffPayrollTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

private const val DEFAULT_DEMO_DAYS = 3L
private const val DEFAULT_DEMO_STAFF_LIMIT = 5L
private const val DEFAULT_DEMO_DEVICE_LIMIT = 2L
private const val DEMO_POLICY_DOC = "demoPolicy"
private const val DEMO_DEVICE_HISTORY_COLLECTION = "demoDeviceHistory"
private const val DEMO_LEADS_COLLECTION = "demoLeads"
private const val DEMO_ACTIVATIONS_COLLECTION = "demoActivations"
private const val LEAD_POLICY_DOC = "leadPolicy"
private const val LEADS_COLLECTION = "leads"

private fun defaultDemoModules(): Map<String, Boolean> = mapOf("attendance" to true, "leave" to true, "salary" to true, "payroll" to true, "reports" to true)

/** Returns null when the value is invalid; callers must not silently substitute a demo policy. */
private fun validateIndianMobileNumber(raw: String): String? {
    val phone = raw.trim()
    return when {
        phone.isBlank() -> "Mobile number is required."
        phone.length != 10 -> "Enter a valid 10-digit Indian mobile number."
        !phone.all(Char::isDigit) -> "Enter a valid 10-digit Indian mobile number."
        phone.first() !in '6'..'9' -> "Enter a valid 10-digit Indian mobile number."
        else -> null
    }
}

private fun readStrictDemoPolicy(d: DocumentSnapshot): DemoPolicy {
    if (!d.exists()) throw IllegalStateException("Demo policy is not configured.")
    val duration = d.getLong("durationDays") ?: throw IllegalStateException("Demo policy duration is not configured.")
    val staff = d.getLong("staffLimit") ?: throw IllegalStateException("Demo policy staff limit is not configured.")
    val devices = d.getLong("deviceLimit") ?: throw IllegalStateException("Demo policy device limit is not configured.")
    if (duration < 1L || staff < 1L || devices < 1L) throw IllegalStateException("Demo policy contains invalid limits.")
    val modules = (d.get("modules") as? Map<*, *>)
        ?.mapNotNull { (k, v) -> if (k != null && v is Boolean) k.toString() to v else null }
        ?.toMap()
        ?: emptyMap()
    return DemoPolicy(duration, staff, devices, defaultDemoModules() + modules)
}

private data class DemoPolicy(val durationDays: Long, val staffLimit: Long, val deviceLimit: Long, val modules: Map<String, Boolean>)
private data class DemoCoreResult(
    val licenseId: String, val clientId: String, val tenantId: String, val activationNumber: Long,
    val validUntil: Timestamp, val staffLimit: Long, val deviceLimit: Long,
    val modules: Map<String, Boolean>, val firstActivation: Boolean
)
private data class LeadPolicy(val statuses: List<String>)

private fun defaultLeadStatuses(): List<String> = listOf("NEW", "CONTACTED", "DEMO_GIVEN", "FOLLOW_UP", "CONVERTED", "LOST")

private enum class UserRole(val value: String) {
    DEVELOPER("DEVELOPER"), SUPER_ADMIN("SUPER_ADMIN"), ADMIN("ADMIN"), USER("USER")
}

private data class UserRecord(
    val uid: String,
    val email: String,
    val tenantId: String,
    val role: UserRole,
    val status: String,
    val staffId: String,
    val displayName: String
)

private data class TenantRecord(
    val documentId: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val ownershipStatus: String,
    val primaryEmail: String,
    val activeLicenseId: String,
    val superAdminEmails: List<String>
)

private data class LicenseRecord(
    val documentId: String,
    val licenseId: String,
    val clientId: String,
    val tenantId: String,
    val customerEmail: String,
    val customerName: String,
    val customerPhone: String,
    val licenseType: String,
    val status: String,
    val validFrom: Timestamp?,
    val validUntil: Timestamp?,
    val staffLimit: Long,
    val deviceLimit: Long,
    val modules: Map<String, Boolean>
)

private data class DeviceRecord(
    val id: String,
    val tenantId: String,
    val uid: String,
    val staffId: String,
    val status: String,
    val deviceName: String,
    val platform: String,
    val slotNumber: Long
)

private data class SessionRecord(
    val id: String,
    val uid: String,
    val staffId: String,
    val deviceId: String,
    val status: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val firebaseReady = try {
            FirebaseApp.initializeApp(this) != null || FirebaseApp.getApps(this).isNotEmpty()
        } catch (_: Exception) { false }

        setContent {
            StaffPayrollTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AuthenticationRoot(firebaseReady, applicationContext)
                }
            }
        }
    }
}

@Composable
private fun AuthenticationRoot(firebaseReady: Boolean, context: Context) {
    if (!firebaseReady) { FirebaseConfigurationError(); return }
    val auth = remember { FirebaseAuth.getInstance() }
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    if (currentUser == null) {
        LoginScreen(auth, onSignedIn = { currentUser = auth.currentUser }, onSignedUp = { currentUser = auth.currentUser })
    } else {
        AccessRouter(
            user = currentUser!!,
            context = context,
            onSignOut = { auth.signOut(); currentUser = null }
        )
    }
}

@Composable
private fun AccessRouter(user: FirebaseUser, context: Context, onSignOut: () -> Unit) {
    var loading by remember(user.uid) { mutableStateOf(true) }
    var error by remember(user.uid) { mutableStateOf("") }
    var userRecord by remember(user.uid) { mutableStateOf<UserRecord?>(null) }
    var developer by remember(user.uid) { mutableStateOf(false) }
    var deviceId by remember(user.uid) { mutableStateOf("") }
    var sessionId by remember(user.uid) { mutableStateOf("") }

    LaunchedEffect(user.uid) {
        loading = true
        error = ""
        userRecord = null
        developer = false
        deviceId = ""
        sessionId = ""

        user.reload().addOnCompleteListener { reloadTask ->
            if (!reloadTask.isSuccessful) {
                loading = false
                error = friendlyAuthError(reloadTask.exception)
                return@addOnCompleteListener
            }
            if (!authIsVerified(user)) {
                loading = false
                error = "Please verify your email address first, then sign in again."
                return@addOnCompleteListener
            }

            user.getIdToken(true).addOnCompleteListener { tokenTask ->
                if (!tokenTask.isSuccessful) {
                    loading = false
                    error = friendlyAuthError(tokenTask.exception)
                    return@addOnCompleteListener
                }

                val roleFromClaim = tokenTask.result?.let { accessRole(it) }
                if (roleFromClaim == UserRole.DEVELOPER) {
                    developer = true
                    loading = false
                    return@addOnCompleteListener
                }

                val db = FirebaseFirestore.getInstance()
                db.collection("users").document(user.uid).get().addOnCompleteListener { userTask ->
                    if (!userTask.isSuccessful) {
                        loading = false
                        error = firestoreFriendlyError(userTask.exception)
                        return@addOnCompleteListener
                    }

                    val doc = userTask.result
                    if (doc == null || !doc.exists()) {
                        // A verified customer with no user record is expected to be
                        // in the license-based first-time onboarding flow.
                        loading = false
                        return@addOnCompleteListener
                    }

                    val record = userRecordFrom(doc)
                    if (record.status != "ACTIVE") {
                        loading = false
                        error = "Your user account is inactive. Please contact your Customer Super Admin."
                        return@addOnCompleteListener
                    }

                    userRecord = record
                    if (record.role == UserRole.SUPER_ADMIN || record.role == UserRole.ADMIN || record.role == UserRole.USER) {
                        ensureDeviceAndSession(context, record) { ok, sid, did, message ->
                            deviceId = did
                            sessionId = sid
                            loading = false
                            if (!ok) error = message
                        }
                    } else {
                        loading = false
                        error = "Unsupported user role. Please contact the License Team."
                    }
                }
            }
        }
    }

    if (loading) {
        LoadingScreen("Verifying account and access…")
        return
    }
    if (error.isNotBlank()) {
        ErrorScreen(error, onSignOut)
        return
    }
    if (developer) {
        DeveloperScreen(user.email.orEmpty(), onSignOut)
        return
    }

    val record = userRecord
    if (record == null) {
        CustomerOnboardingScreen(user, onSignOut)
        return
    }

    when (record.role) {
        UserRole.SUPER_ADMIN -> CustomerSuperAdminScreen(record, deviceId, sessionId, onSignOut)
        UserRole.ADMIN -> SimpleRoleScreen("Admin", record, deviceId, sessionId, onSignOut)
        UserRole.USER -> SimpleRoleScreen("Staff / User", record, deviceId, sessionId, onSignOut)
        UserRole.DEVELOPER -> DeveloperScreen(user.email.orEmpty(), onSignOut)
    }
}

@Composable
private fun LoginScreen(auth: FirebaseAuth, onSignedIn: () -> Unit, onSignedUp: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var demoPolicy by remember { mutableStateOf<DemoPolicy?>(null) }
    var demoPolicyLoading by remember { mutableStateOf(true) }
    var demoPolicyError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        demoPolicyLoading = true
        demoPolicyError = ""
        FirebaseFirestore.getInstance().collection("system").document(DEMO_POLICY_DOC).get()
            .addOnCompleteListener { task ->
                demoPolicyLoading = false
                if (task.isSuccessful && task.result?.exists() == true) {
                    try {
                        demoPolicy = readStrictDemoPolicy(task.result!!)
                    } catch (e: Exception) {
                        demoPolicy = null
                        demoPolicyError = e.message ?: "Demo policy is not configured."
                    }
                } else if (task.isSuccessful) {
                    demoPolicy = null
                    demoPolicyError = "Demo policy is not configured right now."
                } else {
                    demoPolicy = null
                    demoPolicyError = firestoreFriendlyError(task.exception)
                }
            }
    }

    BackHandler(enabled = showSignUp || showDemo) {
        if (showDemo) showDemo = false else showSignUp = false
    }

    if (showSignUp) {
        SignUpScreen(auth, email, onBack = { showSignUp = false }, onSignedUp = onSignedUp)
        return
    }
    if (showDemo) {
        DemoSignupScreen(auth, email, onBack = { showDemo = false }, onActivated = onSignedUp)
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.brand_app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Licensing + Customer Onboarding", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it; message = "" }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, enabled = !busy)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it; message = "" }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val e = email.trim()
                if (e.isBlank() || password.isBlank()) { message = "Email and password are required."; return@Button }
                busy = true
                auth.signInWithEmailAndPassword(e, password).addOnCompleteListener { task ->
                    busy = false
                    if (task.isSuccessful) onSignedIn() else message = friendlyAuthError(task.exception)
                }
            }, Modifier.fillMaxWidth(), enabled = !busy
        ) { if (busy) CircularProgressIndicator() else Text("Sign in") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showSignUp = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Create Customer Account") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showDemo = true },
            Modifier.fillMaxWidth(),
            enabled = !busy && !demoPolicyLoading && demoPolicy != null
        ) {
            if (demoPolicyLoading) CircularProgressIndicator()
            else Text("Start ${demoPolicy?.durationDays ?: ""}-Day Free Demo")
        }
        if (demoPolicyError.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(demoPolicyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val e = email.trim()
                if (e.isBlank()) message = "Enter your email first."
                else auth.sendPasswordResetEmail(e).addOnCompleteListener { task ->
                    message = if (task.isSuccessful) "Password reset email sent." else friendlyAuthError(task.exception)
                }
            }, Modifier.fillMaxWidth(), enabled = !busy
        ) { Text("Forgot password?") }
        if (message.isNotBlank()) { Spacer(Modifier.height(14.dp)); Text(message, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SignUpScreen(auth: FirebaseAuth, initialEmail: String, onBack: () -> Unit, onSignedUp: () -> Unit) {
    BackHandler { onBack() }
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Customer Account", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Use the email registered by the License Team. No separate license key is required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Customer email") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(confirm, { confirm = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val e = email.trim()
            when {
                e.isBlank() -> message = "Email is required."
                password.length < 6 -> message = "Password must be at least 6 characters."
                password != confirm -> message = "Passwords do not match."
                else -> {
                    busy = true
                    auth.createUserWithEmailAndPassword(e, password).addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            busy = false
                            message = friendlyAuthError(task.exception)
                            return@addOnCompleteListener
                        }
                        auth.currentUser?.sendEmailVerification()?.addOnCompleteListener { verifyTask ->
                            busy = false
                            if (verifyTask.isSuccessful) {
                                message = "Account created. Verify your email, then sign in again to complete customer onboarding."
                                auth.signOut()
                            } else message = "Account created, but verification email could not be sent. Please try again."
                        }
                    }
                }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy) { if (busy) CircularProgressIndicator() else Text("Create account") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth(), enabled = !busy) { Text("Back to sign in") }
        if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message, color = MaterialTheme.colorScheme.error) }
    }
}


@Composable
private fun DemoSignupScreen(auth: FirebaseAuth, initialEmail: String, onBack: () -> Unit, onActivated: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var waitingForVerification by remember { mutableStateOf(false) }
    var blockedByUsedDemo by remember { mutableStateOf(false) }
    var leadSubmitted by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf<DemoPolicy?>(null) }
    var policyLoading by remember { mutableStateOf(true) }
    var resendBusy by remember { mutableStateOf(false) }
    var resendCooldown by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceId = remember { getStableDeviceId(context) }

    LaunchedEffect(Unit) {
        policyLoading = true
        db.collection("system").document(DEMO_POLICY_DOC).get().addOnCompleteListener { t ->
            policyLoading = false
            if (t.isSuccessful && t.result?.exists() == true) {
                try { policy = readStrictDemoPolicy(t.result!!) }
                catch (e: Exception) { message = e.message ?: "Demo policy is not configured." }
            } else if (t.isSuccessful) {
                message = "Demo policy is not configured right now."
            } else message = firestoreFriendlyError(t.exception)
        }
    }

    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown -= 1
        }
    }

    fun submitLead() {
        val mail = email.trim().lowercase(Locale.ROOT)
        when {
            customerName.isBlank() -> message = "Your name is required."
            businessName.isBlank() -> message = "Business name is required."
            mail.isBlank() || !mail.contains("@") -> message = "Enter a valid email address."
            phone.isBlank() -> message = "Mobile number is required so our Sales Team can call you."
            validateIndianMobileNumber(phone) != null -> message = validateIndianMobileNumber(phone)!!
            else -> {
                busy = true
                db.collection(LEADS_COLLECTION).add(mapOf(
                    "customerName" to customerName.trim(),
                    "businessName" to businessName.trim(),
                    "email" to mail,
                    "phone" to phone.trim(),
                    "deviceId" to deviceId,
                    "leadType" to "DEMO_BLOCKED",
                    "source" to "SELF_SERVICE_DEMO_BLOCKED",
                    "reason" to "DEVICE_DEMO_ALREADY_USED",
                    "status" to "NEW",
                    "followUpDate" to null,
                    "remark" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "createdByUid" to auth.currentUser?.uid.orEmpty()
                )).addOnCompleteListener { t ->
                    busy = false
                    if (t.isSuccessful) {
                        leadSubmitted = true
                        message = "Thank you. Our Sales Team will call you shortly."
                    } else message = firestoreFriendlyError(t.exception)
                }
            }
        }
    }

    fun activateDemo() {
        val current = auth.currentUser ?: run { message = "Please create or sign in to the account first."; return }
        if (policy == null) { message = "Demo policy is still loading. Please try again."; return }
        busy = true
        current.reload().addOnCompleteListener { r ->
            if (!r.isSuccessful) { busy = false; message = friendlyAuthError(r.exception); return@addOnCompleteListener }
            val u = auth.currentUser
            if (u == null) { busy = false; message = "Your authentication session has expired. Please sign in again."; return@addOnCompleteListener }
            u.getIdToken(true).addOnCompleteListener { tokenTask ->
                if (!tokenTask.isSuccessful) { busy = false; message = friendlyAuthError(tokenTask.exception); return@addOnCompleteListener }
                val refreshedUser = auth.currentUser
                val verifiedClaim = tokenTask.result?.claims?.get("email_verified") == true
                if (refreshedUser?.isEmailVerified != true || !verifiedClaim) {
                    busy = false
                    message = "Please verify your email, then tap 'I Have Verified My Email'."
                    return@addOnCompleteListener
                }

                val uid = refreshedUser.uid
                val mail = refreshedUser.email?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (mail.isBlank()) { busy = false; message = "Your Firebase account does not have a valid email address."; return@addOnCompleteListener }
                val tenantId = "demo-$uid"
                val tenantRef = db.collection("tenants").document(tenantId)
                val userRef = db.collection("users").document(uid)
                val policyRef = db.collection("system").document(DEMO_POLICY_DOC)
                val deviceHistoryRef = db.collection(DEMO_DEVICE_HISTORY_COLLECTION).document(deviceId)

                // Read the existing state first. The actual activation is then a single
                // atomic batch write. This avoids a transaction whose read/write rule
                // evaluation can obscure which licensing record failed.
                db.collection("system").document(DEMO_POLICY_DOC).get().addOnCompleteListener { policyTask ->
                    if (!policyTask.isSuccessful) { busy = false; message = firestoreFriendlyError(policyTask.exception); return@addOnCompleteListener }
                    val p = try { readStrictDemoPolicy(policyTask.result!!) } catch (e: Exception) {
                        busy = false; message = e.message ?: "Demo policy is not configured."; return@addOnCompleteListener
                    }
                    db.collection(DEMO_DEVICE_HISTORY_COLLECTION).document(deviceId).get().addOnCompleteListener { historyTask ->
                        if (!historyTask.isSuccessful) { busy = false; message = firestoreFriendlyError(historyTask.exception); return@addOnCompleteListener }
                        val history = historyTask.result!!
                        val resetAllowed = history.exists() && history.getBoolean("resetAvailable") == true
                        if (history.exists() && !resetAllowed) {
                            busy = false
                            blockedByUsedDemo = true
                            message = "Demo already used on this device/account. Kindly buy the license."
                            return@addOnCompleteListener
                        }
                        db.collection("tenants").document(tenantId).get().addOnCompleteListener { tenantTask ->
                            if (!tenantTask.isSuccessful) { busy = false; message = firestoreFriendlyError(tenantTask.exception); return@addOnCompleteListener }
                            val existingTenant = tenantTask.result!!
                            if (existingTenant.exists() && existingTenant.getString("accountType") != "DEMO") {
                                busy = false; message = "A paid license already exists for this account. Please contact Sales Team."; return@addOnCompleteListener
                            }
                            db.collection("users").document(uid).get().addOnCompleteListener { userTask ->
                                if (!userTask.isSuccessful) { busy = false; message = firestoreFriendlyError(userTask.exception); return@addOnCompleteListener }
                                val existingUser = userTask.result!!
                                if (existingUser.exists() && existingUser.getString("accountType") != "DEMO") {
                                    busy = false; message = "This account is already linked to a paid license. Please contact Sales Team."; return@addOnCompleteListener
                                }

                                val now = Timestamp.now()
                                val activationCount = (history.getLong("activationCount") ?: 0L) + 1L
                                val licenseId = "DEMO-LIC-$uid-$activationCount"
                                val clientId = existingTenant.getString("clientId") ?: "DEMO-$uid"
                                val cal = Calendar.getInstance().apply { time = now.toDate(); add(Calendar.DAY_OF_YEAR, p.durationDays.toInt()) }
                                val endTs = Timestamp(cal.time)
                                val licenseRef = db.collection("licenses").document(licenseId)

                                val batch = db.batch()
                                batch.set(tenantRef, mapOf(
                                    "clientId" to clientId, "clientName" to businessName.trim(), "accountType" to "DEMO",
                                    "status" to "ACTIVE", "primaryEmail" to mail, "ownershipStatus" to "ASSIGNED", "ownerUid" to uid,
                                    "activeLicenseId" to licenseId, "activeDeviceCount" to 0L,
                                    "superAdminUids" to listOf(uid), "superAdminEmails" to listOf(mail), "nextStaffNumber" to 1L,
                                    "demoActivatedAt" to now, "demoValidUntil" to endTs, "customerName" to customerName.trim(),
                                    "phone" to phone.trim(), "createdAt" to (existingTenant.getTimestamp("createdAt") ?: now),
                                    "createdBy" to (existingTenant.getString("createdBy") ?: uid), "updatedAt" to now
                                ), SetOptions.merge())
                                batch.set(licenseRef, mapOf(
                                    "licenseId" to licenseId, "clientId" to clientId, "tenantId" to tenantId,
                                    "customerEmail" to mail, "customerName" to customerName.trim(), "customerPhone" to phone.trim(),
                                    "licenseType" to "DEMO", "status" to "ACTIVE", "validFrom" to now, "validUntil" to endTs,
                                    "staffLimit" to p.staffLimit, "deviceLimit" to p.deviceLimit, "modules" to p.modules,
                                    "issuedAt" to now, "issuedBy" to if (activationCount == 1L) "SELF_SERVICE_DEMO" else "SALES_RESET_SELF_SERVICE",
                                    "createdAt" to now, "updatedAt" to now, "version" to 1L, "demoActivationNumber" to activationCount
                                ))
                                batch.set(userRef, mapOf(
                                    "uid" to uid, "email" to mail, "tenantId" to tenantId, "role" to "SUPER_ADMIN",
                                    "status" to "ACTIVE", "staffId" to "ST-000001", "displayName" to customerName.trim(),
                                    "accountType" to "DEMO", "createdAt" to (existingUser.getTimestamp("createdAt") ?: now), "updatedAt" to now
                                ), SetOptions.merge())
                                batch.set(deviceHistoryRef, mapOf(
                                    "deviceId" to deviceId, "firstUid" to (history.getString("firstUid") ?: uid), "lastUid" to uid,
                                    "firstEmail" to (history.getString("firstEmail") ?: mail), "lastEmail" to mail,
                                    "firstUsedAt" to (history.getTimestamp("firstUsedAt") ?: now), "lastUsedAt" to now,
                                    "activationCount" to activationCount, "resetAvailable" to false,
                                    "resetUsedAt" to if (resetAllowed) now else null, "resetUsedBy" to if (resetAllowed) uid else null,
                                    "status" to "USED"
                                ), SetOptions.merge())

                                batch.commit().addOnCompleteListener { coreTask ->
                                    busy = false
                                    if (!coreTask.isSuccessful) {
                                        message = firestoreFriendlyError(coreTask.exception)
                                        return@addOnCompleteListener
                                    }
                                    val activationType = if (activationCount == 1L) "FIRST_DEMO" else "RE_DEMO_AFTER_RESET"
                                    val activationRef = db.collection(DEMO_ACTIVATIONS_COLLECTION).document()
                                    val commonLead = mapOf(
                                        "leadType" to "DEMO_ACTIVATION", "source" to "DEMO_ACTIVATION", "activationId" to activationRef.id,
                                        "activationNumber" to activationCount, "activationType" to activationType, "licenseId" to licenseId,
                                        "clientId" to clientId, "tenantId" to tenantId, "customerName" to customerName.trim(),
                                        "businessName" to businessName.trim(), "email" to mail, "phone" to phone.trim(), "deviceId" to deviceId,
                                        "status" to "NEW", "followUpDate" to null, "remark" to "", "createdAt" to Timestamp.now(),
                                        "createdByUid" to uid, "updatedAt" to Timestamp.now(), "updatedByUid" to uid
                                    )
                                    val activationData = mapOf(
                                        "licenseId" to licenseId, "clientId" to clientId, "tenantId" to tenantId,
                                        "customerName" to customerName.trim(), "businessName" to businessName.trim(), "customerEmail" to mail,
                                        "customerPhone" to phone.trim(), "deviceId" to deviceId, "activationNumber" to activationCount,
                                        "activationType" to activationType, "validUntil" to endTs, "staffLimit" to p.staffLimit,
                                        "deviceLimit" to p.deviceLimit, "modules" to p.modules, "activatedAt" to Timestamp.now(), "activatedBy" to uid
                                    )
                                    val historyData = mapOf(
                                        "licenseId" to licenseId, "clientId" to clientId, "tenantId" to tenantId, "customerEmail" to mail,
                                        "action" to if (activationCount == 1L) "DEMO_ACTIVATED" else "DEMO_REACTIVATED_AFTER_RESET",
                                        "licenseType" to "DEMO", "newStaffLimit" to p.staffLimit, "newDeviceLimit" to p.deviceLimit,
                                        "newValidUntil" to endTs, "demoActivationNumber" to activationCount, "deviceId" to deviceId,
                                        "changedAt" to Timestamp.now(), "changedBy" to uid
                                    )
                                    val auditBatch = db.batch()
                                    auditBatch.set(activationRef, activationData)
                                    auditBatch.set(db.collection(LEADS_COLLECTION).document(), commonLead)
                                    auditBatch.set(db.collection("licenseHistory").document(), historyData)
                                    auditBatch.commit().addOnCompleteListener { auditTask ->
                                        if (auditTask.isSuccessful) message = "Free demo activated successfully."
                                        else message = "Free demo activated successfully. Some activity history could not be saved yet."
                                        onActivated()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Start Free Demo", style = MaterialTheme.typography.headlineMedium)
        if (!blockedByUsedDemo) {
            Text("Verify your email first. Demo is activated after email verification.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            policy?.let { Text("Current demo policy: ${it.durationDays} days • ${it.staffLimit} staff • ${it.deviceLimit} devices", style = MaterialTheme.typography.labelLarge) }
            OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true,enabled=!busy&&!waitingForVerification)
            OutlinedTextField(customerName,{customerName=it},Modifier.fillMaxWidth(),label={Text("Your Name")},singleLine=true,enabled=!busy&&!waitingForVerification)
            OutlinedTextField(businessName,{businessName=it},Modifier.fillMaxWidth(),label={Text("Business Name")},singleLine=true,enabled=!busy&&!waitingForVerification)
            OutlinedTextField(phone,{phone=it.filter(Char::isDigit).take(10)},Modifier.fillMaxWidth(),label={Text("Mobile Number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),enabled=!busy&&!waitingForVerification)
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy&&!waitingForVerification)
            OutlinedTextField(confirm,{confirm=it},Modifier.fillMaxWidth(),label={Text("Confirm Password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy&&!waitingForVerification)
            Button(onClick={val e=email.trim();when{e.isBlank()->message="Email is required.";!e.contains("@")->message="Enter a valid email address.";customerName.isBlank()->message="Your name is required.";businessName.isBlank()->message="Business name is required.";phone.isBlank()->message="Mobile number is required.";validateIndianMobileNumber(phone)!=null->message=validateIndianMobileNumber(phone)!!;password.length<6->message="Password must be at least 6 characters.";password!=confirm->message="Passwords do not match.";policyLoading->message="Demo policy is still loading. Please wait a moment.";policy==null->message="Demo policy is not configured right now. Please contact the License Team.";else->{busy=true;auth.createUserWithEmailAndPassword(e,password).addOnCompleteListener{t->if(!t.isSuccessful){busy=false;message=friendlyAuthError(t.exception);return@addOnCompleteListener};waitingForVerification=true
                        val createdUser = auth.currentUser
                        if (createdUser == null) {
                            busy=false
                            message="Account created, but the verification session could not be opened. Please sign in again."
                        } else {
                            createdUser.sendEmailVerification().addOnCompleteListener { v ->
                                busy=false
                                if (v.isSuccessful) {
                                    resendCooldown = 30
                                    message="Account created. Verification email sent. Check your inbox and spam folder, verify your email, then tap 'I Have Verified My Email'."
                                } else {
                                    message="Account created, but the verification email could not be sent. ${friendlyAuthError(v.exception)}"
                                }
                            }
                        }}}}},Modifier.fillMaxWidth(),enabled=!busy&&!waitingForVerification){if(busy)CircularProgressIndicator()else Text("Create Demo Account")}
            OutlinedButton(onClick={
                val e=email.trim()
                if(e.isBlank()||password.isBlank()){message="Enter the existing demo account email and password.";return@OutlinedButton}
                busy=true
                auth.signInWithEmailAndPassword(e,password).addOnCompleteListener{t->
                    if(!t.isSuccessful){busy=false;message=friendlyAuthError(t.exception);return@addOnCompleteListener}
                    auth.currentUser?.reload()?.addOnCompleteListener{r->
                        if(!r.isSuccessful){busy=false;message=friendlyAuthError(r.exception);return@addOnCompleteListener}
                        if(auth.currentUser?.isEmailVerified!=true){busy=false;message="Please verify your email first.";return@addOnCompleteListener}
                        activateDemo()
                    }
                }
            },Modifier.fillMaxWidth(),enabled=!busy&&!waitingForVerification){Text("Sign in Existing Demo Account")}

            if(waitingForVerification){
                Button(onClick={activateDemo()},Modifier.fillMaxWidth(),enabled=!busy&&!resendBusy){Text("I Have Verified My Email")}
                OutlinedButton(
                    onClick={
                        val u = auth.currentUser
                        if (u == null) {
                            message = "Please sign in to resend the verification email."
                        } else {
                            resendBusy = true
                            message = ""
                            u.sendEmailVerification().addOnCompleteListener { v ->
                                resendBusy = false
                                if (v.isSuccessful) {
                                    resendCooldown = 30
                                    message = "Verification email sent again. Check your inbox and spam folder."
                                } else {
                                    message = "Verification email could not be sent. ${friendlyAuthError(v.exception)}"
                                }
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled=!busy&&!resendBusy&&resendCooldown==0
                ){
                    if (resendBusy) CircularProgressIndicator()
                    else Text(if (resendCooldown > 0) "Resend in ${resendCooldown}s" else "Resend Verification Email")
                }
            }
        } else {
            Text("Demo Already Used", style = MaterialTheme.typography.headlineSmall)
            Text("This device has already been used for a demo. Kindly buy the license.", color = MaterialTheme.colorScheme.error)
            Text("Please leave your details below. Our Sales Team will contact you for assistance or an additional demo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(customerName,{customerName=it},Modifier.fillMaxWidth(),label={Text("Your Name")},singleLine=true,enabled=!busy&&!leadSubmitted)
            OutlinedTextField(businessName,{businessName=it},Modifier.fillMaxWidth(),label={Text("Business Name")},singleLine=true,enabled=!busy&&!leadSubmitted)
            OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true,enabled=!busy&&!leadSubmitted)
            OutlinedTextField(phone,{phone=it.filter(Char::isDigit).take(10)},Modifier.fillMaxWidth(),label={Text("Mobile Number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),enabled=!busy&&!leadSubmitted)
            Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall)
            if (!leadSubmitted) Button(onClick={submitLead()},Modifier.fillMaxWidth(),enabled=!busy){if(busy)CircularProgressIndicator()else Text("Request Sales Callback")}
            else Text("Lead submitted successfully. Sales Team will contact you.", color = MaterialTheme.colorScheme.primary)
        }
        if(message.isNotBlank()) Text(message, color = if (leadSubmitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=onBack,Modifier.fillMaxWidth(),enabled=!busy){Text("Back to sign in")}
    }
}

private class DemoAlreadyUsedException : Exception("Demo already used on this device/account. Kindly buy the license.")

@Composable
private fun DeveloperScreen(email: String, onSignOut: () -> Unit) {
    var showTenants by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showDemoPolicy by remember { mutableStateOf(false) }
    var showDemoLeads by remember { mutableStateOf(false) }
    var showDemoReset by remember { mutableStateOf(false) }
    var showDemoActivations by remember { mutableStateOf(false) }
    var showLeadPolicy by remember { mutableStateOf(false) }
    val db = remember { FirebaseFirestore.getInstance() }
    LaunchedEffect(Unit) {
        val ref = db.collection("system").document(DEMO_POLICY_DOC)
        ref.get().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result?.exists() != true) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                ref.set(mapOf("durationDays" to DEFAULT_DEMO_DAYS, "staffLimit" to DEFAULT_DEMO_STAFF_LIMIT, "deviceLimit" to DEFAULT_DEMO_DEVICE_LIMIT, "modules" to defaultDemoModules(), "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to uid, "version" to 1L), SetOptions.merge())
            }
        }
        val leadRef = db.collection("system").document(LEAD_POLICY_DOC)
        leadRef.get().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result?.exists() != true) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                leadRef.set(mapOf("statuses" to defaultLeadStatuses(), "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to uid, "version" to 1L), SetOptions.merge())
            }
        }
    }
    if (showTenants || showLicenses || showDemoPolicy || showDemoLeads || showDemoReset || showDemoActivations || showLeadPolicy) {
        BackHandler {
            when {
                showTenants -> showTenants = false
                showLicenses -> showLicenses = false
                showDemoPolicy -> showDemoPolicy = false
                showDemoLeads -> showDemoLeads = false
                showDemoReset -> showDemoReset = false
                showDemoActivations -> showDemoActivations = false
                showLeadPolicy -> showLeadPolicy = false
            }
        }
    }
    if (showTenants) { TenantAndLicenseCreateScreen({ showTenants = false }, onSignOut); return }
    if (showLicenses) { LicenseManagementScreen({ showLicenses = false }, onSignOut); return }
    if (showDemoPolicy) { DemoPolicyScreen({ showDemoPolicy = false }, onSignOut); return }
    if (showDemoLeads) { LeadManagementScreen({ showDemoLeads = false }, onSignOut); return }
    if (showDemoReset) { DemoResetScreen({ showDemoReset = false }, onSignOut); return }
    if (showDemoActivations) { DemoActivationsScreen({ showDemoActivations = false }, onSignOut); return }
    if (showLeadPolicy) { LeadPolicyScreen({ showLeadPolicy = false }, onSignOut); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Developer / License Team", style = MaterialTheme.typography.headlineMedium)
        Text(email.ifBlank { "Authenticated developer" })
        Text("Technical + Licensing Control Plane", style = MaterialTheme.typography.labelLarge)
        Text("Developer manages customer registry and license entitlement metadata. Customer business data is outside this Firebase layer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Create Customer + Issue License", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("No customer-facing license key. Register the customer email, plan limits, modules and common validity.")
            Spacer(Modifier.height(12.dp)); Button({ showTenants = true }, Modifier.fillMaxWidth()) { Text("Create / Issue License") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("License Management", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Update staff/device limits, modules, validity, status and review license history.")
            Spacer(Modifier.height(12.dp)); Button({ showLicenses = true }, Modifier.fillMaxWidth()) { Text("Open License Management") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Demo Policy", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Configure default demo duration, staff limit, device limit and included modules.")
            Spacer(Modifier.height(12.dp)); Button({ showDemoPolicy = true }, Modifier.fillMaxWidth()) { Text("Configure Demo Policy") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Lead Management", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Manage every demo activation, blocked-demo lead and renewal lead with status, follow-up date and remarks.")
            Spacer(Modifier.height(12.dp)); Button({ showDemoLeads = true }, Modifier.fillMaxWidth()) { Text("Open Lead Management") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Reset Demo Device", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Allow Sales Team to grant one additional demo on a previously used device.")
            Spacer(Modifier.height(12.dp)); Button({ showDemoReset = true }, Modifier.fillMaxWidth()) { Text("Reset / Allow Re-Demo") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Demo Activations", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Track every demo activation and re-demo with customer, device, policy and expiry details.")
            Spacer(Modifier.height(12.dp)); Button({ showDemoActivations = true }, Modifier.fillMaxWidth()) { Text("Open Demo Activations") }
        }}
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Lead Status Policy", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp)); Text("Customize the dropdown statuses used by Demo, Sales and Renewal leads.")
            Spacer(Modifier.height(12.dp)); Button({ showLeadPolicy = true }, Modifier.fillMaxWidth()) { Text("Configure Lead Statuses") }
        }}
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun TenantAndLicenseCreateScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var clientName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var staffLimit by remember { mutableStateOf("10") }
    var deviceLimit by remember { mutableStateOf("5") }
    var validityDays by remember { mutableStateOf("365") }
    var licenseType by remember { mutableStateOf("PAID") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var demoPolicy by remember { mutableStateOf<DemoPolicy?>(null) }

    LaunchedEffect(Unit) { db.collection("system").document(DEMO_POLICY_DOC).get().addOnCompleteListener { t -> if(t.isSuccessful && t.result?.exists()==true) demoPolicy=demoPolicyFrom(t.result!!) } }
    fun applyDemoPolicy() { demoPolicy?.let { validityDays=it.durationDays.toString(); staffLimit=it.staffLimit.toString(); deviceLimit=it.deviceLimit.toString() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Issue Customer License", style = MaterialTheme.typography.headlineMedium)
        Text("No customer-facing license key. The customer uses the registered email + password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (licenseType == "PAID") Button(onClick = { licenseType = "PAID" }, Modifier.weight(1f)) { Text("Paid License") }
            else OutlinedButton(onClick = { licenseType = "PAID" }, Modifier.weight(1f)) { Text("Paid License") }
            if (licenseType == "DEMO") Button(onClick = { licenseType = "DEMO"; applyDemoPolicy() }, Modifier.weight(1f)) { Text("Demo") }
            else OutlinedButton(onClick = { licenseType = "DEMO"; applyDemoPolicy() }, Modifier.weight(1f)) { Text("Demo") }
        }
        OutlinedTextField(clientName, { clientName = it }, Modifier.fillMaxWidth(), label = { Text("Customer / Business Name") }, singleLine = true)
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Customer Login Email") }, singleLine = true)
        OutlinedTextField(staffLimit, { staffLimit = it }, Modifier.fillMaxWidth(), label = { Text("Staff Limit") }, singleLine = true, enabled = licenseType != "DEMO")
        OutlinedTextField(deviceLimit, { deviceLimit = it }, Modifier.fillMaxWidth(), label = { Text("Device Limit") }, singleLine = true, enabled = licenseType != "DEMO")
        OutlinedTextField(validityDays, { validityDays = it }, Modifier.fillMaxWidth(), label = { Text("Validity (days)") }, singleLine = true, enabled = licenseType != "DEMO")
        Text(if (licenseType == "DEMO") demoPolicy?.let { "Sales Demo policy: ${it.durationDays} days • ${it.staffLimit} staff • ${it.deviceLimit} devices. Configure from Developer → Demo Policy." } ?: "Demo Policy is loading..." else "Paid license: validity and limits are fully controlled by the License Team.", style = MaterialTheme.typography.bodySmall)
        Text("Modules: Attendance, Leave, Salary, Payroll, Reports — enabled for this implementation.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val name = clientName.trim(); val mail = email.trim().lowercase(Locale.ROOT)
            val staff = staffLimit.toLongOrNull(); val devices = deviceLimit.toLongOrNull(); val days = validityDays.toLongOrNull()
            when {
                name.isBlank() || mail.isBlank() -> message = "Customer name and email are required."
                !mail.contains("@") -> message = "Enter a valid customer email."
                staff == null || staff < 1 -> message = "Staff limit must be at least 1."
                devices == null || devices < 1 -> message = "Device limit must be at least 1."
                days == null || days < 1 -> message = "Validity must be at least 1 day."
                licenseType == "DEMO" && demoPolicy == null -> message = "Demo Policy is not available. Configure it from Developer → Demo Policy first."
                else -> {
                    saving = true; message = ""
                    val tenantRef = db.collection("tenants").document()
                    val licenseRef = db.collection("licenses").document()
                    val counterRef = db.collection("system").document("counters")
                    val developerUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    db.runTransaction { tx ->
                        val counter = tx.get(counterRef)
                        val next = counter.getLong("nextClientNumber") ?: 1L
                        val clientId = "CL-%06d".format(Locale.ROOT, next)
                        val licenseId = "LIC-%06d".format(Locale.ROOT, next)
                        val start = Timestamp.now()
                        val livePolicy = if (licenseType == "DEMO") readStrictDemoPolicy(tx.get(db.collection("system").document(DEMO_POLICY_DOC))) else null
                        val finalDays = livePolicy?.durationDays ?: days
                        val finalStaff = livePolicy?.staffLimit ?: staff
                        val finalDevices = livePolicy?.deviceLimit ?: devices
                        val finalModules = livePolicy?.modules ?: defaultDemoModules()
                        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, finalDays.toInt())
                        val end = Timestamp(cal.time)
                        tx.set(counterRef, mapOf("nextClientNumber" to next + 1L), SetOptions.merge())
                        tx.set(tenantRef, mapOf(
                            "clientId" to clientId, "clientName" to name, "status" to "ACTIVE",
                            "primaryEmail" to mail, "ownershipStatus" to "UNASSIGNED", "ownerUid" to "",
                            "accountType" to licenseType, "activeLicenseId" to licenseRef.id, "activeDeviceCount" to 0L, "superAdminUids" to emptyList<String>(),
                            "superAdminEmails" to emptyList<String>(), "nextStaffNumber" to 1L,
                            "createdAt" to FieldValue.serverTimestamp(), "createdBy" to developerUid,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ))
                        tx.set(licenseRef, mapOf(
                            "licenseId" to licenseId, "clientId" to clientId, "tenantId" to tenantRef.id,
                            "customerEmail" to mail, "licenseType" to licenseType, "status" to "ACTIVE", "validFrom" to start, "validUntil" to end,
                            "staffLimit" to finalStaff, "deviceLimit" to finalDevices,
                            "modules" to finalModules,
                            "issuedAt" to FieldValue.serverTimestamp(), "issuedBy" to developerUid,
                            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(), "version" to 1L
                        ))
                        tx.set(db.collection("licenseHistory").document(), mapOf(
                            "licenseId" to licenseRef.id, "clientId" to clientId, "tenantId" to tenantRef.id,
                            "customerEmail" to mail, "action" to "ISSUE", "newStaffLimit" to finalStaff,
                            "newDeviceLimit" to finalDevices, "newValidUntil" to end, "changedAt" to FieldValue.serverTimestamp(), "changedBy" to developerUid
                        ))
                        clientId
                    }.addOnCompleteListener { task ->
                        saving = false
                        if (task.isSuccessful) { message = "${if (licenseType == "DEMO") "Demo" else "License"} issued. Client ID: ${task.result}"; clientName = ""; email = "" }
                        else message = firestoreFriendlyError(task.exception)
                    }
                }
            }
        }, Modifier.fillMaxWidth(), enabled = !saving) { if (saving) CircularProgressIndicator() else Text("Issue License") }
        if (message.isNotBlank()) Text(message, color = if (message.contains("issued")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back") }
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}



@Composable
private fun DemoActivationsScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var items by remember { mutableStateOf<List<DocumentSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    fun load() {
        loading=true
        db.collection(DEMO_ACTIVATIONS_COLLECTION).orderBy("activatedAt", Query.Direction.DESCENDING).limit(200).get().addOnCompleteListener{t->
            loading=false
            if(t.isSuccessful) items=t.result?.documents.orEmpty() else message=firestoreFriendlyError(t.exception)
        }
    }
    LaunchedEffect(Unit){load()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Demo Activations",style=MaterialTheme.typography.headlineMedium)
        Text("Every first demo and Sales-approved re-demo is recorded here for lead tracking.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(loading)CircularProgressIndicator()
        items.forEach{d->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
            Text(d.getString("businessName")?:"Business",style=MaterialTheme.typography.titleMedium)
            Text("Customer: ${d.getString("customerName")?:"-"}")
            Text("Mobile: ${d.getString("customerPhone")?:"-"}")
            Text("Email: ${d.getString("customerEmail")?:"-"}")
            Text("Device: ${d.getString("deviceId")?:"-"}",style=MaterialTheme.typography.bodySmall)
            Text("Type: ${d.getString("activationType")?:"-"} • #${d.getLong("activationNumber")?:0}")
            Text("Limit: ${d.getLong("staffLimit")?:0} staff • ${d.getLong("deviceLimit")?:0} devices")
            Text("Valid until: ${formatDate(d.getTimestamp("validUntil"))}")
        }}}
        if(items.isEmpty()&&!loading)Text("No demo activations found.")
        if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        OutlinedButton(onClick={load()},Modifier.fillMaxWidth(),enabled=!loading){Text("Refresh")}
        OutlinedButton(onClick=onBack,Modifier.fillMaxWidth()){Text("Back")}
        OutlinedButton(onClick=onSignOut,Modifier.fillMaxWidth()){Text("Sign out")}
    }
}

@Composable
private fun LeadStatusDropdown(current: String, statuses: List<String>, enabled: Boolean, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(current.ifBlank { "Select status" })
                Text("▼")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            statuses.forEach { status ->
                DropdownMenuItem(text = { Text(status) }, onClick = { expanded = false; onSelected(status) })
            }
        }
    }
}

@Composable
private fun LeadManagementScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var leads by remember { mutableStateOf<List<DocumentSnapshot>>(emptyList()) }
    var statuses by remember { mutableStateOf(defaultLeadStatuses()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editStatus by remember { mutableStateOf("NEW") }
    var editFollowUp by remember { mutableStateOf("") }
    var editRemark by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        db.collection("system").document(LEAD_POLICY_DOC).get().addOnCompleteListener { pt ->
            if (pt.isSuccessful && pt.result?.exists() == true) statuses = leadPolicyFrom(pt.result!!).statuses
            db.collection(LEADS_COLLECTION).orderBy("createdAt", Query.Direction.DESCENDING).limit(500).get().addOnCompleteListener { t ->
                loading = false
                if (t.isSuccessful) leads = t.result?.documents.orEmpty() else message = firestoreFriendlyError(t.exception)
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lead Management", style = MaterialTheme.typography.headlineMedium)
        Text("Every demo activation is a lead. Blocked demo requests and post-sale renewal leads are also tracked here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) CircularProgressIndicator()
        leads.forEach { d ->
            val isEditing = editingId == d.id
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${d.getString("businessName") ?: "Business"} • ${d.getString("leadType") ?: "LEAD"}", style = MaterialTheme.typography.titleMedium)
                Text("Name: ${d.getString("customerName") ?: "-"}")
                Text("Mobile: ${d.getString("phone") ?: d.getString("customerPhone") ?: "-"}")
                Text("Email: ${d.getString("email") ?: d.getString("customerEmail") ?: "-"}")
                d.getString("deviceId")?.takeIf { it.isNotBlank() }?.let { Text("Device: $it", style = MaterialTheme.typography.bodySmall) }
                d.getLong("activationNumber")?.let { Text("Demo Activation #$it") }
                if (isEditing) {
                    LeadStatusDropdown(editStatus, statuses, !saving) { editStatus = it }
                    OutlinedTextField(editFollowUp, { editFollowUp = it }, Modifier.fillMaxWidth(), label = { Text("Follow-up Date (dd-MM-yyyy)") }, singleLine = true, enabled = !saving)
                    OutlinedTextField(editRemark, { editRemark = it }, Modifier.fillMaxWidth(), label = { Text("Remark") }, minLines = 2, enabled = !saving)
                    Button(onClick = {
                        val date = if (editFollowUp.isBlank()) null else parseDate(editFollowUp)
                        if (editFollowUp.isNotBlank() && date == null) { message = "Enter follow-up date as dd-MM-yyyy."; return@Button }
                        saving = true
                        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                        db.collection(LEADS_COLLECTION).document(d.id).update(mapOf(
                            "status" to editStatus, "followUpDate" to (date?.let { Timestamp(it) }), "remark" to editRemark.trim(),
                            "updatedAt" to FieldValue.serverTimestamp(), "updatedByUid" to uid
                        )).addOnCompleteListener { t ->
                            saving = false
                            if (t.isSuccessful) { message = "Lead updated successfully."; editingId = null; load() } else message = firestoreFriendlyError(t.exception)
                        }
                    }, Modifier.fillMaxWidth(), enabled = !saving) { Text("Save Lead") }
                    OutlinedButton(onClick = { editingId = null }, Modifier.fillMaxWidth(), enabled = !saving) { Text("Cancel") }
                } else {
                    Text("Status: ${d.getString("status") ?: "NEW"}")
                    Text("Follow-up: ${formatDate(d.getTimestamp("followUpDate"))}")
                    Text("Remark: ${d.getString("remark")?.ifBlank { "-" } ?: "-"}")
                    Button(onClick = {
                        editingId = d.id
                        editStatus = d.getString("status") ?: "NEW"
                        editFollowUp = formatDate(d.getTimestamp("followUpDate")).let { if (it == "-") "" else it }
                        editRemark = d.getString("remark") ?: ""
                    }, Modifier.fillMaxWidth()) { Text("Edit / Follow-up") }
                }
            }}
        }
        if (leads.isEmpty() && !loading) Text("No leads found.")
        if (message.isNotBlank()) Text(message, color = if (message.contains("successfully")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { load() }, Modifier.fillMaxWidth(), enabled = !loading && !saving) { Text("Refresh") }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth(), enabled = !saving) { Text("Back") }
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth(), enabled = !saving) { Text("Sign out") }
    }
}

@Composable
private fun DemoLeadsScreen(onBack: () -> Unit, onSignOut: () -> Unit) = LeadManagementScreen(onBack, onSignOut)

@Composable
private fun DemoResetScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var deviceId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Sales approved additional demo") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Reset / Allow Re-Demo", style = MaterialTheme.typography.headlineMedium)
        Text("This grants exactly one additional self-service demo activation on the specified device. It does not erase previous demo history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(deviceId,{deviceId=it},Modifier.fillMaxWidth(),label={Text("Device ID")},singleLine=true,enabled=!saving)
        OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Customer Email (optional)")},singleLine=true,enabled=!saving)
        OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text("Reason")},singleLine=true,enabled=!saving)
        Button(onClick={
            val did=deviceId.trim()
            if(did.isBlank()){message="Device ID is required.";return@Button}
            saving=true;message=""
            val ref=db.collection(DEMO_DEVICE_HISTORY_COLLECTION).document(did)
            ref.get().addOnCompleteListener{g->
                if(!g.isSuccessful){saving=false;message=firestoreFriendlyError(g.exception);return@addOnCompleteListener}
                if(!g.result.exists()){saving=false;message="No demo history found for this Device ID.";return@addOnCompleteListener}
                val uid=FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                ref.set(mapOf("resetAvailable" to true,"resetAt" to FieldValue.serverTimestamp(),"resetBy" to uid,"resetReason" to reason.trim(),"resetRequestedEmail" to email.trim().lowercase(Locale.ROOT),"status" to "RESET_ALLOWED"),SetOptions.merge()).addOnCompleteListener{t->saving=false;message=if(t.isSuccessful)"Reset approved. Customer can now sign in and start one more demo on this device." else firestoreFriendlyError(t.exception)}
            }
        },Modifier.fillMaxWidth(),enabled=!saving){if(saving)CircularProgressIndicator()else Text("Allow One More Demo")}
        if(message.isNotBlank())Text(message,color=if(message.contains("approved"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=onBack,Modifier.fillMaxWidth(),enabled=!saving){Text("Back")}
        OutlinedButton(onClick=onSignOut,Modifier.fillMaxWidth(),enabled=!saving){Text("Sign out")}
    }
}

@Composable
private fun LeadPolicyScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var statusesText by remember { mutableStateOf(defaultLeadStatuses().joinToString(", ")) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        db.collection("system").document(LEAD_POLICY_DOC).get().addOnCompleteListener { t ->
            loading = false
            if (t.isSuccessful && t.result?.exists() == true) statusesText = leadPolicyFrom(t.result!!).statuses.joinToString(", ")
            else if (!t.isSuccessful) message = firestoreFriendlyError(t.exception)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lead Status Policy", style = MaterialTheme.typography.headlineMedium)
        Text("These values become the dropdown options for Demo, blocked-demo and Renewal leads. You can add, remove or rename them later without changing the lead data structure.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) CircularProgressIndicator()
        OutlinedTextField(statusesText, { statusesText = it }, Modifier.fillMaxWidth(), label = { Text("Lead Statuses (comma separated)") }, minLines = 4, enabled = !saving && !loading)
        Text("Example: NEW, CONTACTED, DEMO_GIVEN, FOLLOW_UP, CONVERTED, LOST", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val list = statusesText.split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
            when {
                list.isEmpty() -> message = "Keep at least one lead status."
                list.size > 50 -> message = "You can keep up to 50 statuses."
                list.any { it.length > 40 } -> message = "Each status must be 40 characters or less."
                !list.contains("NEW") -> message = "NEW must remain available because every new lead starts with NEW."
                else -> {
                    saving = true
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    db.collection("system").document(LEAD_POLICY_DOC).set(mapOf("statuses" to list, "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to uid, "version" to 1L), SetOptions.merge()).addOnCompleteListener { t ->
                        saving = false
                        message = if (t.isSuccessful) "Lead Status Policy saved successfully." else firestoreFriendlyError(t.exception)
                    }
                }
            }
        }, Modifier.fillMaxWidth(), enabled = !saving && !loading) { if (saving) CircularProgressIndicator() else Text("Save Lead Statuses") }
        if (message.isNotBlank()) Text(message, color = if (message.contains("successfully")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth(), enabled = !saving) { Text("Back") }
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth(), enabled = !saving) { Text("Sign out") }
    }
}

@Composable
private fun DemoPolicyScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var durationDays by remember { mutableStateOf(DEFAULT_DEMO_DAYS.toString()) }; var staffLimit by remember { mutableStateOf(DEFAULT_DEMO_STAFF_LIMIT.toString()) }; var deviceLimit by remember { mutableStateOf(DEFAULT_DEMO_DEVICE_LIMIT.toString()) }
    var attendance by remember { mutableStateOf(true) }; var leave by remember { mutableStateOf(true) }; var salary by remember { mutableStateOf(true) }; var payroll by remember { mutableStateOf(true) }; var reports by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }; var saving by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { db.collection("system").document(DEMO_POLICY_DOC).get().addOnCompleteListener { t -> loading=false; if(t.isSuccessful && t.result?.exists()==true){ val p=demoPolicyFrom(t.result!!);durationDays=p.durationDays.toString();staffLimit=p.staffLimit.toString();deviceLimit=p.deviceLimit.toString();attendance=p.modules["attendance"]==true;leave=p.modules["leave"]==true;salary=p.modules["salary"]==true;payroll=p.modules["payroll"]==true;reports=p.modules["reports"]==true } else if(!t.isSuccessful) message=firestoreFriendlyError(t.exception) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Demo Policy",style=MaterialTheme.typography.headlineMedium);Text("Controls defaults for both self-service Free Demo and Sales Team Demo issuance. Changes apply only to new demos; existing demos keep their issued terms.",color=MaterialTheme.colorScheme.onSurfaceVariant);if(loading)CircularProgressIndicator()
        OutlinedTextField(durationDays,{durationDays=it},Modifier.fillMaxWidth(),label={Text("Demo Duration (days)")},singleLine=true,enabled=!saving);OutlinedTextField(staffLimit,{staffLimit=it},Modifier.fillMaxWidth(),label={Text("Staff Limit")},singleLine=true,enabled=!saving);OutlinedTextField(deviceLimit,{deviceLimit=it},Modifier.fillMaxWidth(),label={Text("Device Limit")},singleLine=true,enabled=!saving)
        Text("Modules included in new demos",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){if(attendance)Button({attendance=false},Modifier.weight(1f)){Text("Attendance ✓")}else OutlinedButton({attendance=true},Modifier.weight(1f)){Text("Attendance")};if(leave)Button({leave=false},Modifier.weight(1f)){Text("Leave ✓")}else OutlinedButton({leave=true},Modifier.weight(1f)){Text("Leave")}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){if(salary)Button({salary=false},Modifier.weight(1f)){Text("Salary ✓")}else OutlinedButton({salary=true},Modifier.weight(1f)){Text("Salary")};if(payroll)Button({payroll=false},Modifier.weight(1f)){Text("Payroll ✓")}else OutlinedButton({payroll=true},Modifier.weight(1f)){Text("Payroll")};if(reports)Button({reports=false},Modifier.weight(1f)){Text("Reports ✓")}else OutlinedButton({reports=true},Modifier.weight(1f)){Text("Reports")}}
        Button(onClick={val d=durationDays.toLongOrNull();val st=staffLimit.toLongOrNull();val dv=deviceLimit.toLongOrNull();when{d==null||d<1->message="Demo duration must be at least 1 day.";d>365->message="Demo duration cannot exceed 365 days.";st==null||st<1->message="Staff limit must be at least 1.";st>10000->message="Staff limit cannot exceed 10,000.";dv==null||dv<1->message="Device limit must be at least 1.";dv>100->message="Device limit cannot exceed 100.";!attendance&&!leave&&!salary&&!payroll&&!reports->message="Enable at least one module.";else->{saving=true;val uid=FirebaseAuth.getInstance().currentUser?.uid.orEmpty();db.collection("system").document(DEMO_POLICY_DOC).set(mapOf("durationDays" to d,"staffLimit" to st,"deviceLimit" to dv,"modules" to mapOf("attendance" to attendance,"leave" to leave,"salary" to salary,"payroll" to payroll,"reports" to reports),"updatedAt" to FieldValue.serverTimestamp(),"updatedBy" to uid,"version" to 1L),SetOptions.merge()).addOnCompleteListener{t->saving=false;message=if(t.isSuccessful)"Demo Policy saved successfully. New demos will use these limits." else firestoreFriendlyError(t.exception)}}}},Modifier.fillMaxWidth(),enabled=!saving&&!loading){if(saving)CircularProgressIndicator()else Text("Save Demo Policy")}
        if(message.isNotBlank())Text(message,color=if(message.contains("successfully"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error);OutlinedButton(onBack,Modifier.fillMaxWidth(),enabled=!saving){Text("Back")};OutlinedButton(onSignOut,Modifier.fillMaxWidth(),enabled=!saving){Text("Sign out")}
    }
}

@Composable
private fun LicenseManagementScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    BackHandler { onBack() }
    val db = remember { FirebaseFirestore.getInstance() }
    var licenses by remember { mutableStateOf<List<LicenseRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<LicenseRecord?>(null) }
    var staff by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf("") }
    var validUntil by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun load() {
        loading = true
        db.collection("licenses").orderBy("validUntil", Query.Direction.ASCENDING).get().addOnCompleteListener { task ->
            loading = false
            if (task.isSuccessful) licenses = task.result?.documents?.map { licenseFrom(it) } ?: emptyList()
            else message = firestoreFriendlyError(task.exception)
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("License Management", style = MaterialTheme.typography.headlineMedium)
        if (loading) CircularProgressIndicator()
        licenses.forEach { item ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text("${item.clientId} — ${item.customerEmail}", style = MaterialTheme.typography.titleMedium)
                Text("${item.licenseId} | ${item.licenseType} | ${item.status}")
                Text("Staff ${item.staffLimit} | Devices ${item.deviceLimit} | Valid until ${formatDate(item.validUntil)}")
                Button(onClick = {
                    selected = item; staff = item.staffLimit.toString(); devices = item.deviceLimit.toString(); validUntil = formatDate(item.validUntil); status = item.status
                }) { Text("Edit") }
                OutlinedButton(onClick = {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    db.collection(LEADS_COLLECTION).add(mapOf(
                        "leadType" to "RENEWAL", "source" to "POST_SALE_CLIENT_LIST",
                        "licenseId" to item.licenseId, "clientId" to item.clientId, "tenantId" to item.tenantId,
                        "customerName" to item.customerName, "businessName" to item.clientId, "email" to item.customerEmail, "phone" to item.customerPhone,
                        "status" to "NEW", "followUpDate" to null, "remark" to "Renewal follow-up",
                        "createdAt" to FieldValue.serverTimestamp(), "createdByUid" to uid, "updatedAt" to FieldValue.serverTimestamp(), "updatedByUid" to uid
                    )).addOnCompleteListener { t -> message = if (t.isSuccessful) "Renewal lead created for ${item.clientId}. Open Lead Management to set follow-up and remarks." else firestoreFriendlyError(t.exception) }
                }) { Text("Create Renewal Lead") }
            }}
        }
        selected?.let { item ->
            Divider()
            Text("Edit ${item.licenseId}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(staff, { staff = it }, Modifier.fillMaxWidth(), label = { Text("Staff Limit") }, singleLine = true)
            OutlinedTextField(devices, { devices = it }, Modifier.fillMaxWidth(), label = { Text("Device Limit") }, singleLine = true)
            OutlinedTextField(validUntil, { validUntil = it }, Modifier.fillMaxWidth(), label = { Text("Valid Until (dd-MM-yyyy)") }, singleLine = true)
            OutlinedTextField(status, { status = it.uppercase(Locale.ROOT) }, Modifier.fillMaxWidth(), label = { Text("Status: ACTIVE / SUSPENDED") }, singleLine = true)
            Button(onClick = {
                val s = staff.toLongOrNull(); val d = devices.toLongOrNull(); val date = parseDate(validUntil)
                if (s == null || s < 1 || d == null || d < 1 || date == null || status !in listOf("ACTIVE", "SUSPENDED")) { message = "Enter valid limits, date and ACTIVE/SUSPENDED status."; return@Button }
                val old = item
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val updates = mapOf("staffLimit" to s, "deviceLimit" to d, "validUntil" to Timestamp(date), "status" to status, "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to uid, "version" to (old.licenseId.filter { it.isDigit() }.toLongOrNull() ?: 1L) + 1L)
                db.collection("licenses").document(old.documentId).update(updates).addOnCompleteListener { task ->
                    if (!task.isSuccessful) { message = firestoreFriendlyError(task.exception); return@addOnCompleteListener }
                    db.collection("licenseHistory").document().set(mapOf(
                        "licenseId" to old.documentId, "clientId" to old.clientId, "tenantId" to old.tenantId, "customerEmail" to old.customerEmail,
                        "action" to "LICENSE_UPDATE", "oldStaffLimit" to old.staffLimit, "newStaffLimit" to s,
                        "oldDeviceLimit" to old.deviceLimit, "newDeviceLimit" to d, "oldValidUntil" to old.validUntil,
                        "newValidUntil" to Timestamp(date), "oldStatus" to old.status, "newStatus" to status,
                        "changedAt" to FieldValue.serverTimestamp(), "changedBy" to uid
                    )).addOnCompleteListener { hist ->
                        message = if (hist.isSuccessful) "License updated successfully." else firestoreFriendlyError(hist.exception)
                        selected = null; load()
                    }
                }
            }, Modifier.fillMaxWidth()) { Text("Save License Update") }
            OutlinedButton(onClick = { selected = null }, Modifier.fillMaxWidth()) { Text("Cancel Edit") }
        }
        if (message.isNotBlank()) Text(message, color = if (message.contains("successfully")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back") }
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun CustomerOnboardingScreen(user: FirebaseUser, onSignOut: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var license by remember { mutableStateOf<LicenseRecord?>(null) }
    var tenant by remember { mutableStateOf<TenantRecord?>(null) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(user.uid) {
        user.reload().addOnCompleteListener { reloadTask ->
            if (!reloadTask.isSuccessful) { loading = false; message = friendlyAuthError(reloadTask.exception); return@addOnCompleteListener }
            if (authIsVerified(user).not()) { loading = false; message = "Please verify your email address first, then sign in again."; return@addOnCompleteListener }
            val email = user.email?.trim()?.lowercase(Locale.ROOT).orEmpty()
            db.collection("licenses").whereEqualTo("customerEmail", email).whereEqualTo("status", "ACTIVE").whereGreaterThanOrEqualTo("validUntil", Timestamp.now()).limit(1).get().addOnCompleteListener { task ->
            if (!task.isSuccessful) { loading = false; message = firestoreFriendlyError(task.exception); return@addOnCompleteListener }
            val doc = task.result?.documents?.firstOrNull()
            if (doc == null) {
                loading = false
                message = if (user.isEmailVerified) {
                    "No active license is registered for this email. If this is a Demo account, open Start Free Demo and complete email verification to activate it."
                } else {
                    "Please verify your email address first, then sign in again."
                }
                return@addOnCompleteListener
            }
            val lic = licenseFrom(doc); license = lic
            db.collection("tenants").document(lic.tenantId).get().addOnCompleteListener { tenantTask ->
                loading = false
                if (tenantTask.isSuccessful && tenantTask.result?.exists() == true) tenant = tenantFrom(tenantTask.result!!)
                else message = firestoreFriendlyError(tenantTask.exception)
            }
        }
    }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Customer Onboarding", style = MaterialTheme.typography.headlineMedium)
        if (loading) CircularProgressIndicator()
        else if (license == null || tenant == null) Text(message, color = MaterialTheme.colorScheme.error)
        else {
            val lic = license!!; val ten = tenant!!
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(ten.clientId, style = MaterialTheme.typography.labelLarge)
                Text(ten.clientName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("Staff limit: ${lic.staffLimit}")
                Text("Device limit: ${lic.deviceLimit}")
                Text("Valid until: ${formatDate(lic.validUntil)}")
                Text("Separate license key: Not required")
            }}
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your Name") }, singleLine = true)
            Button(onClick = {
                if (name.trim().isBlank()) { message = "Your name is required."; return@Button }
                saving = true; message = ""
                val tenantRef = db.collection("tenants").document(lic.tenantId)
                db.runTransaction { tx ->
                    val snap = tx.get(tenantRef)
                    val owner = snap.getString("ownerUid").orEmpty()
                    val ownership = snap.getString("ownershipStatus") ?: "UNASSIGNED"
                    if (ownership != "UNASSIGNED" || owner.isNotBlank()) throw IllegalStateException("This license has already been claimed by another account.")
                    val nextStaff = snap.getLong("nextStaffNumber") ?: 1L
                    val staffId = "ST-%06d".format(Locale.ROOT, nextStaff)
                    tx.update(tenantRef, mapOf(
                        "ownerUid" to user.uid, "ownershipStatus" to "ASSIGNED",
                        "superAdminUids" to FieldValue.arrayUnion(user.uid),
                        "superAdminEmails" to FieldValue.arrayUnion(user.email?.lowercase(Locale.ROOT).orEmpty()),
                        "nextStaffNumber" to nextStaff + 1L, "updatedAt" to FieldValue.serverTimestamp()
                    ))
                    staffId
                }.addOnCompleteListener { task ->
                    if (!task.isSuccessful) { saving = false; message = task.exception?.message ?: "Unable to complete onboarding."; return@addOnCompleteListener }
                    val staffId = task.result ?: "ST-000001"
                    db.collection("users").document(user.uid).set(mapOf(
                        "uid" to user.uid, "email" to user.email?.lowercase(Locale.ROOT).orEmpty(), "tenantId" to lic.tenantId,
                        "role" to "SUPER_ADMIN", "status" to "ACTIVE", "staffId" to staffId,
                        "displayName" to name.trim(), "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
                    )).addOnCompleteListener { userTask ->
                        saving = false
                        if (userTask.isSuccessful) { message = "Onboarding complete. Please sign in again to continue."; onSignOut() } else message = firestoreFriendlyError(userTask.exception)
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !saving) { if (saving) CircularProgressIndicator() else Text("Complete Onboarding") }
        }
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun CustomerSuperAdminScreen(user: UserRecord, deviceId: String, sessionId: String, onSignOut: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var tenant by remember { mutableStateOf<TenantRecord?>(null) }
    var license by remember { mutableStateOf<LicenseRecord?>(null) }
    var devices by remember { mutableStateOf<List<DeviceRecord>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionRecord>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun load() {
        loading = true
        db.collection("tenants").document(user.tenantId).get().addOnCompleteListener { t ->
            if (!t.isSuccessful || t.result == null || !t.result!!.exists()) { loading = false; message = firestoreFriendlyError(t.exception); return@addOnCompleteListener }
            val ten = tenantFrom(t.result!!); tenant = ten
            db.collection("licenses").document(ten.activeLicenseId).get().addOnCompleteListener { l ->
                license = if (l.isSuccessful && l.result?.exists() == true) licenseFrom(l.result!!) else null
                db.collection("devices").whereEqualTo("tenantId", user.tenantId).get().addOnCompleteListener { d ->
                    devices = if (d.isSuccessful) d.result?.documents?.map { deviceFrom(it) } ?: emptyList() else emptyList()
                    db.collection("sessions").whereEqualTo("tenantId", user.tenantId).whereEqualTo("status", "ACTIVE").get().addOnCompleteListener { s ->
                        sessions = if (s.isSuccessful) s.result?.documents?.map { sessionFrom(it) } ?: emptyList() else emptyList()
                        loading = false
                    }
                }
            }
        }
    }
    LaunchedEffect(user.tenantId) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Super Admin", style = MaterialTheme.typography.headlineMedium)
        Text(user.displayName.ifBlank { user.email })
        if (loading) CircularProgressIndicator()
        tenant?.let { ten ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("${ten.clientId} — ${ten.clientName}", style = MaterialTheme.typography.titleLarge)
                Text("Tenant ownership: ${ten.ownershipStatus}")
                Text("Super Admins: ${ten.superAdminEmails.joinToString(", ")}")
            }}
        }
        license?.let { lic ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Active License", style = MaterialTheme.typography.titleLarge)
                Text("Staff limit: ${lic.staffLimit}")
                Text("Device limit: ${lic.deviceLimit}")
                Text("Valid until: ${formatDate(lic.validUntil)}")
                Text("Status: ${lic.status}")
            }}
        }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Device / Session Management", style = MaterialTheme.typography.titleLarge)
            Text("You can remotely revoke a Staff/Admin session. Developer intervention is not required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            devices.forEach { d ->
                val activeSession = sessions.firstOrNull { it.deviceId == d.id && it.status == "ACTIVE" }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.staffId} — ${d.deviceName}")
                        Text("${d.status} | ${d.platform}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (activeSession != null) {
                        OutlinedButton(onClick = {
                            val batch = db.batch()
                            batch.update(db.collection("sessions").document(activeSession.id), mapOf("status" to "REVOKED", "revokedAt" to FieldValue.serverTimestamp(), "revokedBy" to user.uid, "logoutReason" to "REMOTE_LOGOUT"))
                            batch.update(db.collection("devices").document(d.id), mapOf("status" to "REVOKED", "revokedAt" to FieldValue.serverTimestamp(), "revokedBy" to user.uid))
                            if (d.slotNumber > 0) batch.update(db.collection("deviceSlots").document("${user.tenantId}_${d.slotNumber}"), mapOf("status" to "REVOKED", "updatedAt" to FieldValue.serverTimestamp(), "revokedBy" to user.uid))
                            batch.commit().addOnCompleteListener { task -> message = if (task.isSuccessful) "Remote logout completed for ${d.staffId}. Device slot released." else firestoreFriendlyError(task.exception); load() }
                        }) { Text("Force Logout") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (devices.isEmpty()) Text("No registered devices.")
        }}
        if (message.isNotBlank()) Text(message, color = if (message.contains("completed")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
        Text("Current device: $deviceId | Session: $sessionId", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SimpleRoleScreen(title: String, user: UserRecord, deviceId: String, sessionId: String, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp)); Text(user.displayName.ifBlank { user.email })
        Spacer(Modifier.height(8.dp)); Text("Staff ID: ${user.staffId}")
        Spacer(Modifier.height(8.dp)); Text("Tenant: ${user.tenantId}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp)); OutlinedButton(onClick = onSignOut, Modifier.fillMaxWidth()) { Text("Sign out") }
        Spacer(Modifier.height(12.dp)); Text("Device: $deviceId\nSession: $sessionId", style = MaterialTheme.typography.bodySmall)
    }
}

private fun getStableDeviceId(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim().orEmpty()
    val stable = if (androidId.isNotBlank()) androidId else UUID.randomUUID().toString()
    return "DEV-${stable.lowercase(Locale.ROOT)}"
}

private fun ensureDeviceAndSession(context: Context, user: UserRecord, done: (Boolean, String, String, String) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val prefs = context.getSharedPreferences("staff_payroll_identity", Context.MODE_PRIVATE)
    val did = getStableDeviceId(context)
    val deviceRef = db.collection("devices").document(did)
    db.collection("sessions").whereEqualTo("uid", user.uid).whereEqualTo("status", "ACTIVE").get().addOnCompleteListener { activeTask ->
        if (!activeTask.isSuccessful) { done(false, "", did, firestoreFriendlyError(activeTask.exception)); return@addOnCompleteListener }
        val other = activeTask.result?.documents?.firstOrNull { it.getString("deviceId") != did }
        if (other != null) { done(false, "", did, "This staff/admin account is already active on another device. Ask the customer Super Admin to Force Logout the old session."); return@addOnCompleteListener }
        deviceRef.get().addOnCompleteListener { deviceTask ->
            if (!deviceTask.isSuccessful) { done(false, "", did, firestoreFriendlyError(deviceTask.exception)); return@addOnCompleteListener }
            val existing = deviceTask.result
            val proceedWithDevice: () -> Unit = {
                val existingSession = activeTask.result?.documents?.firstOrNull { it.getString("deviceId") == did }
                if (existingSession != null) {
                    val sid = existingSession.id
                    db.collection("sessions").document(sid).update("lastSeenAt", FieldValue.serverTimestamp()).addOnCompleteListener { t ->
                        done(t.isSuccessful, sid, did, if (t.isSuccessful) "" else firestoreFriendlyError(t.exception))
                    }
                } else {
                    val sessionRef = db.collection("sessions").document("SES-${UUID.randomUUID()}")
                    sessionRef.set(mapOf(
                        "sessionId" to sessionRef.id, "tenantId" to user.tenantId, "uid" to user.uid, "staffId" to user.staffId,
                        "deviceId" to did, "loginAt" to FieldValue.serverTimestamp(), "lastSeenAt" to FieldValue.serverTimestamp(), "status" to "ACTIVE"
                    )).addOnCompleteListener { t -> done(t.isSuccessful, if (t.isSuccessful) sessionRef.id else "", did, if (t.isSuccessful) "" else firestoreFriendlyError(t.exception)) }
                }
            }
            if (existing != null && existing.exists()) { proceedWithDevice(); return@addOnCompleteListener }
            db.collection("licenses").whereEqualTo("tenantId", user.tenantId).whereEqualTo("status", "ACTIVE").whereGreaterThanOrEqualTo("validUntil", Timestamp.now()).limit(1).get().addOnCompleteListener { licTask ->
                val lic = licTask.result?.documents?.firstOrNull()?.let { licenseFrom(it) }
                if (lic == null) { done(false, "", did, "No active license was found."); return@addOnCompleteListener }
                val maxSlots = minOf(lic.deviceLimit, 100L).toInt()
                val slotIds = (1..maxSlots).map { "${user.tenantId}_$it" }
                db.collection("deviceSlots").whereEqualTo("tenantId", user.tenantId).whereEqualTo("status", "ACTIVE").get().addOnCompleteListener { slotsTask ->
                    if (!slotsTask.isSuccessful) { done(false, "", did, firestoreFriendlyError(slotsTask.exception)); return@addOnCompleteListener }
                    val used = slotsTask.result?.documents?.mapNotNull { it.getLong("slotNumber")?.toInt() }?.toSet() ?: emptySet()
                    val freeSlot = (1..maxSlots).firstOrNull { it !in used }
                    if (freeSlot == null) { done(false, "", did, "Device limit reached (${lic.deviceLimit}). Ask the Super Admin to revoke an old device or the License Team to increase the limit."); return@addOnCompleteListener }
                    val slotRef = db.collection("deviceSlots").document("${user.tenantId}_$freeSlot")
                    val batch = db.batch()
                    batch.set(deviceRef, mapOf("deviceId" to did, "tenantId" to user.tenantId, "uid" to user.uid, "staffId" to user.staffId, "status" to "ACTIVE", "deviceName" to android.os.Build.MODEL, "platform" to "Android", "slotNumber" to freeSlot, "licenseId" to lic.documentId, "registeredAt" to FieldValue.serverTimestamp(), "lastSeenAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                    batch.set(slotRef, mapOf("tenantId" to user.tenantId, "slotNumber" to freeSlot, "deviceId" to did, "status" to "ACTIVE", "uid" to user.uid, "staffId" to user.staffId, "licenseId" to lic.documentId, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                    batch.commit().addOnCompleteListener { t -> if (t.isSuccessful) proceedWithDevice() else done(false, "", did, firestoreFriendlyError(t.exception)) }
                }
            }
        }
    }
}

private fun authIsVerified(user: FirebaseUser): Boolean = user.isEmailVerified

private fun accessRole(token: GetTokenResult): UserRole? = UserRole.entries.firstOrNull { it.value == token.claims["role"]?.toString()?.uppercase(Locale.ROOT) }

private fun userRecordFrom(d: DocumentSnapshot): UserRecord = UserRecord(
    uid = d.id, email = d.getString("email") ?: "", tenantId = d.getString("tenantId") ?: "",
    role = UserRole.entries.firstOrNull { it.value == (d.getString("role") ?: "USER") } ?: UserRole.USER,
    status = d.getString("status") ?: "ACTIVE", staffId = d.getString("staffId") ?: "", displayName = d.getString("displayName") ?: ""
)

private fun tenantFrom(d: DocumentSnapshot): TenantRecord = TenantRecord(
    documentId = d.id, clientId = d.getString("clientId") ?: "", clientName = d.getString("clientName") ?: "",
    status = d.getString("status") ?: "ACTIVE", ownershipStatus = d.getString("ownershipStatus") ?: "UNASSIGNED",
    primaryEmail = d.getString("primaryEmail") ?: "", activeLicenseId = d.getString("activeLicenseId") ?: "",
    superAdminEmails = (d.get("superAdminEmails") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
)

private fun leadPolicyFrom(d: DocumentSnapshot): LeadPolicy {
    val list = (d.get("statuses") as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { v -> v.isNotBlank() } }?.distinct().orEmpty()
    return LeadPolicy(if (list.contains("NEW")) list else defaultLeadStatuses())
}

private fun demoPolicyFrom(d: DocumentSnapshot): DemoPolicy = readStrictDemoPolicy(d)

private fun licenseFrom(d: DocumentSnapshot): LicenseRecord = LicenseRecord(
    documentId = d.id, licenseId = d.getString("licenseId") ?: d.id, clientId = d.getString("clientId") ?: "",
    tenantId = d.getString("tenantId") ?: "", customerEmail = d.getString("customerEmail") ?: "",
    customerName = d.getString("customerName") ?: "", customerPhone = d.getString("customerPhone") ?: "",
    licenseType = d.getString("licenseType") ?: "PAID", status = d.getString("status") ?: "", validFrom = d.getTimestamp("validFrom"), validUntil = d.getTimestamp("validUntil"),
    staffLimit = d.getLong("staffLimit") ?: 0L, deviceLimit = d.getLong("deviceLimit") ?: 0L,
    modules = (d.get("modules") as? Map<*, *>)?.mapNotNull { (k, v) -> if (k != null && v is Boolean) k.toString() to v else null }?.toMap() ?: emptyMap()
)

private fun deviceFrom(d: DocumentSnapshot) = DeviceRecord(d.id, d.getString("tenantId") ?: "", d.getString("uid") ?: "", d.getString("staffId") ?: "", d.getString("status") ?: "", d.getString("deviceName") ?: "Unknown", d.getString("platform") ?: "Android", d.getLong("slotNumber") ?: 0L)
private fun sessionFrom(d: DocumentSnapshot) = SessionRecord(d.id, d.getString("uid") ?: "", d.getString("staffId") ?: "", d.getString("deviceId") ?: "", d.getString("status") ?: "")

private fun formatDate(ts: Timestamp?): String = if (ts == null) "-" else SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).format(ts.toDate())
private fun parseDate(text: String): java.util.Date? = try { SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply { isLenient = false }.parse(text.trim()) } catch (_: Exception) { null }

@Composable private fun LoadingScreen(text: String) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(text) } }
@Composable private fun ErrorScreen(message: String, onSignOut: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Access verification failed", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp)); Text(message, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(20.dp)); OutlinedButton(onClick = onSignOut) { Text("Return to sign in") } } }
@Composable private fun FirebaseConfigurationError() { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Firebase configuration error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)); Text("Verify google-services.json and Firebase Android configuration.") } }

private fun firestoreFriendlyError(exception: Exception?): String {
    val m = exception?.message.orEmpty()
    return when {
        m.contains("PERMISSION_DENIED", true) -> "Firestore permission denied. Please deploy the latest Firestore rules from GitHub Actions. Details: ${m.take(220)}"
        m.contains("FAILED_PRECONDITION", true) || m.contains("index", true) -> "Firestore index/configuration is required. Details: ${m.take(220)}"
        m.contains("network", true) -> "Network error. Please check your internet connection."
        m.isNotBlank() -> "Firestore error: ${m.take(220)}"
        else -> "Unable to access licensing data. Please try again."
    }
}

private fun friendlyAuthError(exception: Exception?): String {
    val m = exception?.message.orEmpty().lowercase(Locale.ROOT)
    return when {
        "email-already-in-use" in m -> "An account already exists for this email. Please sign in."
        "no user record" in m || "user-not-found" in m -> "No account was found for this email."
        "wrong password" in m || "invalid-credential" in m -> "Email or password is incorrect."
        "invalid email" in m -> "Please enter a valid email address."
        "weak-password" in m -> "Password is too weak. Use at least 6 characters."
        "too many requests" in m -> "Too many attempts. Please try again later."
        "network" in m -> "Network error. Please check your internet connection."
        else -> "Authentication failed. Please try again."
    }
}
