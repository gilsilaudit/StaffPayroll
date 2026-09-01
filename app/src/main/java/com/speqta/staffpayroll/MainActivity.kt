package com.speqta.staffpayroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import com.speqta.staffpayroll.ui.theme.StaffPayrollTheme

private enum class UserRole(val claimValue: String) {
    SUPER_ADMIN("SUPER_ADMIN"),
    ADMIN("ADMIN"),
    USER("USER")
}

private data class AccessContext(
    val role: UserRole,
    val tenantId: String?
)

private data class TenantRecord(
    val id: String,
    val clientName: String,
    val status: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val firebaseReady = try {
            FirebaseApp.initializeApp(this) != null ||
                FirebaseApp.getApps(this).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        setContent {
            StaffPayrollTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    M3AuthenticationScreen(firebaseReady)
                }
            }
        }
    }
}

@Composable
private fun M3AuthenticationScreen(firebaseReady: Boolean) {
    if (!firebaseReady) {
        FirebaseConfigurationError()
        return
    }

    val auth = remember { FirebaseAuth.getInstance() }
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    if (currentUser == null) {
        LoginScreen(auth) { currentUser = auth.currentUser }
    } else {
        RoleResolutionScreen(
            user = currentUser!!,
            onSignOut = {
                auth.signOut()
                currentUser = null
            }
        )
    }
}

@Composable
private fun LoginScreen(
    auth: FirebaseAuth,
    onSignedIn: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resetBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(com.speqta.staffpayroll.R.string.brand_app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Milestone 3.2 — Role & Tenant Management",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !busy && !resetBusy
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy && !resetBusy
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val cleanEmail = email.trim()
                if (cleanEmail.isBlank()) {
                    message = "Please enter your email address."
                    return@Button
                }
                if (password.isBlank()) {
                    message = "Please enter your password."
                    return@Button
                }

                busy = true
                message = ""
                auth.signInWithEmailAndPassword(cleanEmail, password)
                    .addOnCompleteListener { task ->
                        busy = false
                        if (task.isSuccessful) {
                            onSignedIn()
                        } else {
                            message = friendlyAuthError(task.exception)
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !resetBusy
        ) {
            if (busy) CircularProgressIndicator()
            else Text("Sign in")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val cleanEmail = email.trim()
                if (cleanEmail.isBlank()) {
                    message = "Enter your email first to reset the password."
                    return@OutlinedButton
                }

                resetBusy = true
                message = ""
                auth.sendPasswordResetEmail(cleanEmail)
                    .addOnCompleteListener { task ->
                        resetBusy = false
                        message = if (task.isSuccessful) {
                            "Password reset email sent. Check your inbox."
                        } else {
                            friendlyAuthError(task.exception)
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !resetBusy
        ) {
            if (resetBusy) CircularProgressIndicator()
            else Text("Forgot password?")
        }
    }
}

@Composable
private fun RoleResolutionScreen(
    user: FirebaseUser,
    onSignOut: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var access by remember { mutableStateOf<AccessContext?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(user.uid) {
        loading = true
        error = ""
        access = null

        user.getIdToken(true).addOnCompleteListener { task ->
            loading = false
            if (task.isSuccessful) {
                task.result?.let { access = accessFromToken(it) }
                    ?: run { error = "Unable to verify your access. Please sign in again." }
            } else {
                error = "Unable to verify your access. Please sign in again."
            }
        }
    }

    if (loading) {
        LoadingScreen("Verifying access…")
        return
    }

    if (error.isNotBlank()) {
        ErrorScreen(error, onSignOut)
        return
    }

    val resolved = access ?: AccessContext(UserRole.USER, null)

    if (resolved.role == UserRole.SUPER_ADMIN) {
        SuperAdminScreen(user.email.orEmpty(), onSignOut)
        return
    }

    if (resolved.tenantId.isNullOrBlank()) {
        AccessNotConfiguredScreen(user.email.orEmpty(), resolved.role, onSignOut)
        return
    }

    when (resolved.role) {
        UserRole.ADMIN -> AdminScreen(user.email.orEmpty(), resolved.tenantId, onSignOut)
        UserRole.USER -> StandardUserScreen(user.email.orEmpty(), resolved.tenantId, onSignOut)
        UserRole.SUPER_ADMIN -> Unit
    }
}

private fun accessFromToken(token: GetTokenResult): AccessContext {
    val roleValue = token.claims["role"]?.toString()?.uppercase(Locale.ROOT)
    val role = UserRole.entries.firstOrNull { it.claimValue == roleValue } ?: UserRole.USER
    val tenantId = token.claims["tenantId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    return AccessContext(role, tenantId)
}

@Composable
private fun SuperAdminScreen(email: String, onSignOut: () -> Unit) {
    var showTenants by remember { mutableStateOf(false) }

    if (showTenants) {
        TenantManagementScreen(
            onBack = { showTenants = false },
            onSignOut = onSignOut
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Super Admin", style = MaterialTheme.typography.headlineMedium)
        Text(email.ifBlank { "Authenticated user" })
        Text("Global / System Level", style = MaterialTheme.typography.labelLarge)

        Text(
            "Super Admin is a global system role and is not tied to a customer tenant.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Tenant Management", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Create and view customer tenants. Client IDs are generated centrally " +
                        "as CL-000001, CL-000002, etc. Deleted IDs are never reused."
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showTenants = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Tenant Management")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Admin Management", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("Reserved for the Admin assignment milestone.")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("License Management", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("Reserved for the locked License & User-Owned Cloud milestone.")
            }
        }

        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun TenantManagementScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }

    var tenants by remember { mutableStateOf<List<TenantRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var clientName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun loadTenants() {
        loading = true
        message = ""
        db.collection("tenants")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnCompleteListener { task ->
                loading = false
                if (task.isSuccessful) {
                    tenants = task.result?.documents?.mapNotNull { doc ->
                        val name = doc.getString("clientName") ?: return@mapNotNull null
                        TenantRecord(
                            id = doc.id,
                            clientName = name,
                            status = doc.getString("status") ?: "ACTIVE"
                        )
                    }.orEmpty()
                } else {
                    message = firestoreFriendlyError(task.exception)
                }
            }
    }

    LaunchedEffect(Unit) {
        loadTenants()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            OutlinedButton(onClick = onSignOut, modifier = Modifier.weight(1f)) {
                Text("Sign out")
            }
        }

        Text("Tenant Management", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Super Admin only • Customer tenant registry",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                showCreate = !showCreate
                clientName = ""
                message = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        ) {
            Text(if (showCreate) "Cancel Create" else "Create Tenant")
        }

        if (showCreate) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("New Tenant", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = clientName,
                        onValueChange = {
                            clientName = it
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Client / Tenant Name *") },
                        singleLine = true,
                        enabled = !saving
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val name = clientName.trim()
                            if (name.isBlank()) {
                                message = "Client / Tenant Name is required."
                                return@Button
                            }

                            saving = true
                            message = ""

                            val counterRef = db.collection("system")
                                .document("counters")
                            val tenantRef = db.collection("tenants").document()
                            val user = FirebaseAuth.getInstance().currentUser

                            db.runTransaction { transaction ->
                                val counterSnapshot = transaction.get(counterRef)
                                val nextNumber =
                                    counterSnapshot.getLong("nextClientNumber") ?: 1L

                                val clientId = "CL-%06d".format(Locale.ROOT, nextNumber)

                                val tenantData = hashMapOf(
                                    "clientId" to clientId,
                                    "clientName" to name,
                                    "status" to "ACTIVE",
                                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                                    "createdByUid" to (user?.uid ?: "")
                                )

                                transaction.set(
                                    counterRef,
                                    mapOf("nextClientNumber" to nextNumber + 1L),
                                    SetOptions.merge()
                                )
                                transaction.set(tenantRef, tenantData)

                                clientId
                            }.addOnCompleteListener { task ->
                                saving = false
                                if (task.isSuccessful) {
                                    val newId = task.result ?: "new tenant"
                                    message = "Tenant created successfully: $newId"
                                    clientName = ""
                                    showCreate = false
                                    loadTenants()
                                } else {
                                    message = firestoreFriendlyError(task.exception)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving
                    ) {
                        if (saving) CircularProgressIndicator()
                        else Text("Save Tenant")
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = if (
                    message.startsWith("Tenant created successfully")
                ) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }

        Text("Existing Tenants", style = MaterialTheme.typography.titleLarge)

        if (loading) {
            CircularProgressIndicator()
        } else if (tenants.isEmpty()) {
            Text(
                "No tenants created yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tenants.forEach { tenant ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            tenant.id,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tenant.clientName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Status: ${tenant.status}")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "P2 intentionally does not provide hard-delete. Client IDs must never be reused. " +
                "Edit/deactivate and Admin assignment will be added in later controlled milestones.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AdminScreen(
    email: String,
    tenantId: String,
    onSignOut: () -> Unit
) {
    SimpleRoleScreen(
        title = "Admin",
        email = email,
        details = "Tenant: $tenantId\nAdministrative access is active for this tenant.",
        onSignOut = onSignOut
    )
}

@Composable
private fun StandardUserScreen(
    email: String,
    tenantId: String,
    onSignOut: () -> Unit
) {
    SimpleRoleScreen(
        title = "Signed in",
        email = email,
        details = "Tenant: $tenantId\nNo administrative role is assigned to this account.",
        onSignOut = onSignOut
    )
}

@Composable
private fun SimpleRoleScreen(
    title: String,
    email: String,
    details: String,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(email.ifBlank { "Authenticated user" })
        Spacer(Modifier.height(16.dp))
        Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun AccessNotConfiguredScreen(
    email: String,
    role: UserRole,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Access not configured", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(email.ifBlank { "Authenticated user" })
        Spacer(Modifier.height(8.dp))
        Text("Role: ${role.claimValue}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "This account is authenticated, but no tenant has been assigned. " +
                "A trusted onboarding/licensing process must assign the tenant before the app can continue.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
private fun ErrorScreen(message: String, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Access verification failed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Return to sign in")
        }
    }
}

@Composable
private fun FirebaseConfigurationError() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Firebase configuration error",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "google-services.json could not be initialized. " +
                "Please verify the Firebase Android configuration."
        )
    }
}

private fun firestoreFriendlyError(exception: Exception?): String {
    val message = exception?.message.orEmpty()
    return when {
        message.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "Firestore permission denied. Check the M3.2-P2 Firestore rules."
        message.contains("network", ignoreCase = true) ->
            "Network error. Please check your internet connection."
        else ->
            "Unable to access tenant data. Please try again."
    }
}

private fun friendlyAuthError(exception: Exception?): String {
    val message = exception?.message.orEmpty().lowercase(Locale.ROOT)
    return when {
        "no user record" in message || "user-not-found" in message ->
            "No account was found for this email."
        "wrong password" in message || "invalid-credential" in message ->
            "Email or password is incorrect."
        "invalid email" in message ->
            "Please enter a valid email address."
        "too many requests" in message ->
            "Too many attempts. Please try again later."
        "network" in message ->
            "Network error. Please check your internet connection."
        else ->
            "Sign-in failed. Please check your email and password and try again."
    }
}
