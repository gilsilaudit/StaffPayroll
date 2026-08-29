package com.speqta.staffpayroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
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
                    FirebaseFoundationScreen(firebaseReady)
                }
            }
        }
    }
}

@Composable
private fun FirebaseFoundationScreen(firebaseReady: Boolean) {
    var status by remember {
        mutableStateOf(
            if (firebaseReady) "Firebase Core initialized"
            else "Firebase configuration not found"
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Staff Payroll", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Milestone 2 — Firebase Foundation",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(24.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodyLarge,
            color = if (firebaseReady)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            status = if (firebaseReady)
                "Firebase configuration is loaded successfully"
            else
                "Firebase configuration is missing"
        }) {
            Text("Check Firebase")
        }
    }
}
