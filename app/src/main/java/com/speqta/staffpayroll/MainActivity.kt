package com.speqta.staffpayroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val firebaseReady = try {
            FirebaseApp.initializeApp(this) != null || FirebaseApp.getApps(this).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        setContent {
            StaffPayrollTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    M3AuthenticationScreen(firebaseReady = firebaseReady)
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
        LoginScreen(
            auth = auth,
            onSignedIn = { currentUser = auth.currentUser }
        )
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
            text = stringResource(com.speqta.staffpayroll.R.string.brand_app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Milestone 3.2 — Role & Tenant Access",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                message = ""
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !busy && !resetBusy
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                message = ""
            },
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
                if (cleanEmail.isEmpty()) {
                    message = "Please enter your email address."
                    return@Button
                }
                if (password.isEmpty()) {
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
            if (busy) {
                CircularProgressIndicator()
            } else {
                Text("Sign in")
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val cleanEmail = email.trim()
                if (cleanEmail.isEmpty()) {
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
            if (resetBusy) {
                CircularProgressIndicator()
            } else {
                Text("Forgot password?")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "M3.2 uses Firebase Authentication custom claims for role and tenant access. " +
                "No user profile is created in developer Firestore.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (message.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = if (message.startsWith("Password reset")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium
            )
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

        user.getIdToken(true)
            .addOnCompleteListener { task ->
                loading = false
                if (task.isSuccessful) {
                    task.result?.let { token ->
                        access = accessFromToken(token)
                    } ?: run {
                        error = "Unable to verify your access. Please sign in again."
                    }
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

    if (resolved.tenantId.isNullOrBlank()) {
        AccessNotConfiguredScreen(
            email = user.email.orEmpty(),
            role = resolved.role,
            onSignOut = onSignOut
        )
        return
    }

    when (resolved.role) {
        UserRole.SUPER_ADMIN -> SuperAdminScreen(
            email = user.email.orEmpty(),
            tenantId = resolved.tenantId,
            onSignOut = onSignOut
        )
        UserRole.ADMIN -> AdminScreen(
            email = user.email.orEmpty(),
            tenantId = resolved.tenantId,
            onSignOut = onSignOut
        )
        UserRole.USER -> StandardUserScreen(
            email = user.email.orEmpty(),
            tenantId = resolved.tenantId,
            onSignOut = onSignOut
        )
    }
}

private fun accessFromToken(token: GetTokenResult): AccessContext {
    val roleValue = token.claims["role"]?.toString()?.uppercase()
    val role = UserRole.entries.firstOrNull { it.claimValue == roleValue } ?: UserRole.USER
    val tenantId = token.claims["tenantId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    return AccessContext(role = role, tenantId = tenantId)
}

@Composable
private fun SuperAdminScreen(
    email: String,
    tenantId: String,
    onSignOut: () -> Unit
) {
    AdminShell(
        title = "Super Admin",
        email = email,
        tenantId = tenantId,
        description = "Highest application-management authority for this tenant. " +
            "This authority is assigned to an account; it is not tied to a fixed email address.",
        cards = listOf(
            "Admin Management" to "Super Admin-only area reserved for adding, changing and revoking Admin access in this tenant.",
            "License Management" to "Reserved for the License & User-Owned Cloud milestone. License authority remains separate from business data.",
            "Tenant Security" to "Role and tenant claims are verified from the Firebase ID token. The Android client cannot grant itself Super Admin access."
        ),
        onSignOut = onSignOut
    )
}

@Composable
private fun AdminScreen(
    email: String,
    tenantId: String,
    onSignOut: () -> Unit
) {
    AdminShell(
        title = "Admin",
        email = email,
        tenantId = tenantId,
        description = "Administrative access is active for this tenant. Super Admin-only controls remain unavailable.",
        cards = listOf(
            "Administration" to "Admin-level application controls can be added here without granting Super Admin authority.",
            "Access restriction" to "The Admin role cannot elevate itself or access Super Admin-only functions."
        ),
        onSignOut = onSignOut
    )
}

@Composable
private fun AdminShell(
    title: String,
    email: String,
    tenantId: String,
    description: String,
    cards: List<Pair<String, String>>,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(email.ifBlank { "Authenticated user" }, style = MaterialTheme.typography.bodyMedium)
        Text("Tenant: $tenantId", style = MaterialTheme.typography.labelLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)

        cards.forEach { (heading, body) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(heading, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun StandardUserScreen(
    email: String,
    tenantId: String,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Signed in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(email.ifBlank { "Authenticated user" })
        Spacer(Modifier.height(6.dp))
        Text("Tenant: $tenantId", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "No administrative role is assigned to this account.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Firebase configuration error",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "google-services.json could not be initialized. " +
                "Please verify the Firebase Android configuration.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun friendlyAuthError(exception: Exception?): String {
    val message = exception?.message.orEmpty().lowercase()
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
