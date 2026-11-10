package dev.tapflow.autoclicker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.Prefs
import dev.tapflow.autoclicker.data.ScriptStore
import dev.tapflow.autoclicker.model.Script
import dev.tapflow.autoclicker.service.ClickerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onPanelOpened: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { ScriptStore(context) }

  var resumeCount by remember { mutableIntStateOf(0) }
  LifecycleResumeEffect(Unit) {
    resumeCount++
    onPauseOrDispose { }
  }

  val connected by ClickerState.connected.collectAsState()
  val libraryVersion by ClickerState.libraryVersion.collectAsState()
  val enabled = remember(resumeCount, connected) { connected || ClickerState.isServiceEnabled(context) }
  val batteryOk = remember(resumeCount) { isIgnoringBatteryOptimizations(context) }

  var showDisclosure by remember { mutableStateOf(false) }
  var declined by remember { mutableStateOf(false) }
  var scripts by remember { mutableStateOf(emptyList<Script>()) }
  var pendingDelete by remember { mutableStateOf<Script?>(null) }

  LaunchedEffect(libraryVersion, resumeCount) {
    scripts = withContext(Dispatchers.IO) { store.list() }
  }

  fun enableService() {
    if (Prefs.disclosureAccepted(context)) openAccessibilitySettings(context) else showDisclosure = true
  }

  fun openPanel(script: Script?) {
    val service = ClickerService.instance
    if (service == null) {
      enableService()
    } else {
      service.openPanel(script)
      onPanelOpened()
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text("TapFlow", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
      Text("Auto clicker: taps, long presses and swipes", style = MaterialTheme.typography.bodyMedium)
    }

    item {
      StatusCard(
        title = if (connected) "Ready" else if (enabled) "Service enabled, starting…" else "Accessibility service is off",
        body = if (connected) {
          "Open the floating panel, add targets with + or ↗, record with ●, or add an image step with 🖼. Then press ▶."
        } else {
          "TapFlow needs its accessibility service turned on to perform taps for you."
        },
        good = connected,
      ) {
        if (connected) {
          Button(onClick = { openPanel(null) }) { Text("Open floating panel") }
        } else {
          Button(onClick = ::enableService) { Text("Turn on accessibility service") }
        }
      }
    }

    if (declined && !connected) {
      item {
        StatusCard(
          title = "Accessibility access declined",
          body = "TapFlow can't tap without it. You can review the explanation again at any time.",
          good = false,
        ) {
          OutlinedButton(onClick = { showDisclosure = true }) { Text("Review explanation") }
        }
      }
    }

    if (!connected) {
      item {
        StatusCard(
          title = "Toggle greyed out?",
          body = "On Android 13+, apps installed outside a store need an extra step: App info → ⋮ menu → " +
            "\"Allow restricted settings\", then turn the service on again.",
          good = null,
        ) {
          OutlinedButton(onClick = { openAppDetails(context) }) { Text("Open app info") }
        }
      }
    }

    if (!batteryOk) {
      item {
        StatusCard(
          title = "Keep TapFlow running",
          body = "Battery optimization can switch the clicker off. Set TapFlow to \"Unrestricted\" / \"Don't optimize\"." +
            if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) " On Xiaomi, also enable Autostart." else "",
          good = null,
        ) {
          OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Battery settings") }
        }
      }
    }

    item {
      Text(
        "Saved scripts",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
      if (scripts.isEmpty()) {
        Text(
          "Nothing saved yet. Use 💾 on the floating panel to save the current targets.",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    items(scripts, key = { it.id }) { script ->
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(script.name, fontWeight = FontWeight.SemiBold)
            Text(
              "${script.targets.size} target${if (script.targets.size == 1) "" else "s"} · ${script.mode.label} · " +
                if (script.loops == 0) "repeat forever" else "${script.loops}×",
              style = MaterialTheme.typography.bodySmall,
            )
          }
          TextButton(onClick = { pendingDelete = script }) { Text("Delete") }
          Spacer(Modifier.width(4.dp))
          Button(onClick = { openPanel(script) }) { Text("Load") }
        }
      }
    }

    item { Spacer(Modifier.padding(8.dp)) }
  }

  if (showDisclosure) {
    AccessibilityDisclosure(
      onAccept = {
        Prefs.setDisclosureAccepted(context, true)
        showDisclosure = false
        declined = false
        openAccessibilitySettings(context)
      },
      onDecline = {
        Prefs.setDisclosureAccepted(context, false)
        showDisclosure = false
        declined = true
      },
    )
  }

  pendingDelete?.let { script ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete \"${script.name}\"?") },
      text = { Text("This script will be removed from this device.") },
      confirmButton = {
        TextButton(onClick = {
          pendingDelete = null
          scope.launch {
            withContext(Dispatchers.IO) { store.delete(script.id) }
            ClickerState.libraryVersion.value++
          }
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}

/** Google Play's required prominent disclosure, shown before sending the user to settings. */
@Composable
private fun AccessibilityDisclosure(onAccept: () -> Unit, onDecline: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text("Accessibility Service use") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TapFlow uses Android's Accessibility Service API to perform taps, long presses and swipes at the screen positions you set, when you press Play.")
        Text("• For image steps only, it takes screenshots to find the images you picked. They are checked on your device and never saved or sent.")
        Text("• It does not read the text or content of other apps.")
        Text("• It does not collect, store remotely or share any personal data.")
        Text("• Your scripts are saved only on this device.")
        Text("Without this permission TapFlow cannot click for you. On the next screen, find TapFlow and turn it on.")
      }
    },
    confirmButton = { Button(onClick = onAccept) { Text("Agree and continue") } },
    dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
  )
}

@Composable
private fun StatusCard(title: String, body: String, good: Boolean?, actions: @Composable () -> Unit) {
  val colors = when (good) {
    true -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    false -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    null -> CardDefaults.cardColors()
  }
  Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(body, style = MaterialTheme.typography.bodyMedium)
      actions()
    }
  }
}

private fun openAccessibilitySettings(context: Context) =
  context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

private fun openAppDetails(context: Context) =
  context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
  )

private fun openBatterySettings(context: Context) =
  context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
  context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
