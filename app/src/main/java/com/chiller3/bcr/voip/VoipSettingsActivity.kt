package com.chiller3.bcr.voip

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chiller3.bcr.ui.theme.AppTheme

class VoipSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { VoipSettingsScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun VoipSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(VoipTargets.enabled(context)) }
    var automatic by remember { mutableStateOf(VoipTargets.automatic(context)) }
    val audio = context.getSystemService(AudioManager::class.java)
    val audioMode = audio.mode
    val shellPermission = context.packageManager.checkPermission(
        "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT", "com.android.shell"
    ) == PackageManager.PERMISSION_GRANTED
    val status = remember(refresh) { VoipStatus.message(context) }
    val lastFile = remember(refresh) { VoipStatus.lastFile(context) }
    val isRunning = remember(refresh) { VoipStatus.running(context) }
    val installed = remember {
        VoipTargets.known.map { target ->
            val found = runCatching {
                context.packageManager.getPackageInfo(target.packageName, 0)
                true
            }.getOrDefault(false)
            target to found
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VoIP call recording (experimental)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Separate Root AudioPolicy capture path based on JemRec and ShizuCallRecorder research. " +
                "It currently records the VOIP PLAYBACK channel only, not verified two-sided audio. " +
                "All capture is off by default. Test only with participants' permission.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Device diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("Audio mode: $audioMode (communication mode: ${VoipTargets.isCommunicationMode(audioMode)})")
        Text("Android Shell has VOIP capture permission: $shellPermission")
        Text("Actual Root process identity, AudioPolicy registration and sound output still require on-device testing.")
        Text("Select apps", style = MaterialTheme.typography.titleMedium)

        installed.forEach { (target, isInstalled) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(target.name)
                    Text(
                        if (isInstalled) target.packageName else "Not installed: ${target.packageName}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = target.packageName in enabled,
                    onCheckedChange = { on ->
                        VoipTargets.setEnabled(context, target.packageName, on)
                        enabled = VoipTargets.enabled(context)
                    },
                    enabled = isInstalled,
                )
            }
            if (target.packageName in enabled && isInstalled) {
                OutlinedButton(
                    onClick = {
                        VoipRecorderService.start(context, target.packageName)
                        refresh++
                    },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manual test: capture ${target.name} call") }
            }
        }

        Text("Automatic detection (optional)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use ongoing call notifications")
                Text(
                    "Only selected apps, CATEGORY_CALL + ongoing + communication audio mode. " +
                        "Not every messenger exposes such notifications.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = automatic,
                onCheckedChange = {
                    automatic = it
                    VoipTargets.setAutomatic(context, it)
                },
            )
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Android notification access") }
        Text(
            "Grant BCR notification access only if using automatic detection. Android may block " +
                "background foreground-service starts; these are reported instead of bypassed.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Capture status", style = MaterialTheme.typography.titleMedium)
        Text(if (isRunning) "A recording attempt is running" else "No recording currently reported")
        Text(status)
        if (lastFile != null) Text("Last saved file: $lastFile")
        Text("Successful files appear under Recordings/BCR-VoIP as WAV; absence or silence means capture failed.")
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh status")
        }
        Button(
            onClick = {
                VoipRecorderService.stop(context)
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop VoIP capture") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
