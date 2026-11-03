package com.chiller3.bcr.settings

import android.content.pm.PackageManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chiller3.bcr.root.RootBridge
import com.chiller3.bcr.ui.theme.AppTheme
import kotlinx.coroutines.launch

class IntegrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                IntegrationScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun IntegrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootResult by remember { mutableStateOf("Root not checked") }
    var restrictionResult by remember { mutableStateOf("Not run") }
    var busy by remember { mutableStateOf(false) }

    val permissions = listOf(
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CONTROL_INCALL_EXPERIENCE",
    )
    val granted = permissions.map { name ->
        name.substringAfterLast('.') + ": " +
            if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED)
                "granted (Java check)" else "denied (Java check)"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BCR · LSPosed + Root", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Experimental integration. Enable the BCR module for the system " +
                "scope in LSPosed and reboot. The original Magisk ZIP path remains available.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Permission checks", style = MaterialTheme.typography.titleMedium)
        granted.forEach { Text(it) }
        Text(
            "These Java results do not prove native AudioPolicy accepts VOICE_CALL, " +
                "or that Telecom has bound the InCallService.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val result = RootBridge.checkRoot()
                    rootResult = result.message
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check KernelSU / su") }
        Text(rootResult, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val result = RootBridge.exemptCallLog(context)
                    restrictionResult = result.message
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Fix READ_CALL_LOG restriction with Root") }
        Text(restrictionResult, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Run the restriction helper only after unlocking the device. It modifies " +
                "the hard-restricted call log permission, not audio capture permissions.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
