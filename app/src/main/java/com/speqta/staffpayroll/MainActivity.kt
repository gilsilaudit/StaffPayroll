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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.speqta.staffpayroll.ui.theme.StaffPayrollTheme

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
        AuthenticatedScreen(
            email = currentUser?.email.orEmpty(),
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
            text = "Staff Payroll",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Milestone 3.1 — Secure Authentication",
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
            text = "M3.1 does not create users or store user profiles in Firestore. " +
                "Authentication is handled by Firebase Authentication.",
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
private fun AuthenticatedScreen(
    email: String,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Authentication successful",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Signed in as",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = email.ifBlank { "Authenticated user" },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "M3.1 authentication foundation is active. Role-based " +
                "Super Admin/Admin authorization is not assigned in this milestone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign out")
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
