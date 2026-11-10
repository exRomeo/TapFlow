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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import dev.tapflow.autoclicker.model.PanelMode
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
  // Off now, but it has run before: Android (usually a battery manager) or the user switched it off.
  val switchedOff = !enabled && remember(resumeCount) { Prefs.wasConnected(context) }
  val guide = remember { oemGuideFor() }
  var oemGuideDone by remember { mutableStateOf(Prefs.oemGuideDone(context)) }
  var stopOnTouch by remember { mutableStateOf(Prefs.stopOnTouch(context)) }
  var keepScreenOn by remember { mutableStateOf(Prefs.keepScreenOn(context)) }

  var showDisclosure by remember { mutableStateOf(false) }
  // Someone who agreed to an older explanation is asked again about the new use.
  var showUpdatedDisclosure by remember {
    mutableStateOf(Prefs.acceptedDisclosureVersion(context).let { it in 1 until Prefs.DISCLOSURE_VERSION })
  }
  var showPrivacy by remember { mutableStateOf(false) }
  var declined by remember { mutableStateOf(false) }
  var scripts by remember { mutableStateOf(emptyList<Script>()) }
  var pendingDelete by remember { mutableStateOf<Script?>(null) }
  var details by remember { mutableStateOf<Script?>(null) }
  var showAppearance by remember { mutableStateOf(false) }

  LaunchedEffect(libraryVersion, resumeCount) {
    scripts = withContext(Dispatchers.IO) { store.list() }
  }

  fun enableService() {
    if (Prefs.hasCurrentConsent(context)) openAccessibilitySettings(context) else showDisclosure = true
  }

  fun openPanel(script: Script?, mode: PanelMode? = null) {
    val service = ClickerService.instance
    if (service == null) {
      enableService()
    } else {
      service.openPanel(script, mode)
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
        title = when {
          connected -> "Ready"
          enabled -> "Service enabled, starting…"
          switchedOff -> "TapFlow was switched off"
          else -> "Accessibility service is off"
        },
        body = when {
          connected ->
            "Pick a mode below. The floating panel then shows only the tools that mode needs."
          switchedOff ->
            "If you didn't turn it off yourself, your phone's battery manager probably did. " +
              "Turn it back on, then follow \"Keep TapFlow running\" below so it stays on."
          else -> "TapFlow needs its accessibility service turned on to perform taps for you."
        },
        good = connected,
      ) {
        if (connected) {
          OutlinedButton(onClick = { openPanel(null) }) { Text("Reopen last panel") }
        } else {
          Button(onClick = ::enableService) { Text(if (switchedOff) "Turn it back on" else "Turn on accessibility service") }
        }
      }
    }

    if (connected) {
      item { ModePicker(onPick = { openPanel(null, it) }) }
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

    if (!batteryOk || !oemGuideDone || switchedOff) {
      item {
        StatusCard(
          title = "Keep TapFlow running (${guide.brand})",
          body = "Phones switch background apps off to save battery. Do these once:\n" +
            guide.steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
          good = null,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!batteryOk) {
              OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Battery") }
            }
            OutlinedButton(onClick = { openOemSettings(context, guide) }) { Text("Phone settings") }
          }
          if (!oemGuideDone) {
            TextButton(onClick = {
              Prefs.setOemGuideDone(context, true)
              oemGuideDone = true
            }) { Text("I've done this") }
          }
        }
      }
    }

    item {
      StatusCard(
        title = "Panel and markers",
        body = "Size, opacity, orientation, and shrinking the panel while running.",
        good = null,
      ) {
        if (showAppearance) {
          AppearanceSettings()
          TextButton(onClick = { showAppearance = false }) { Text("Hide") }
        } else {
          OutlinedButton(onClick = { showAppearance = true }) { Text("Customize") }
        }
      }
    }

    item {
      StatusCard(title = "While a script runs", body = "Scripts always stop when a call starts or the screen turns off.", good = null) {
        SettingSwitch("Stop when I touch the screen", stopOnTouch) {
          stopOnTouch = it
          Prefs.setStopOnTouch(context, it)
        }
        SettingSwitch("Keep the screen on", keepScreenOn) {
          keepScreenOn = it
          Prefs.setKeepScreenOn(context, it)
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
      Card(onClick = { details = script }, modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(script.name, fontWeight = FontWeight.SemiBold)
            Text(scriptSummary(script), style = MaterialTheme.typography.bodySmall)
            Text("Tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
          }
          TextButton(onClick = { pendingDelete = script }) { Text("Delete") }
          Spacer(Modifier.width(4.dp))
          Button(onClick = { openPanel(script) }) { Text("Load") }
        }
      }
    }

    item {
      TextButton(onClick = { showPrivacy = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text("Privacy policy")
      }
      Spacer(Modifier.padding(8.dp))
    }
  }

  if (showUpdatedDisclosure && !showDisclosure) {
    AccessibilityDisclosure(
      updated = true,
      onAccept = {
        Prefs.setDisclosureAccepted(context, true)
        showUpdatedDisclosure = false
      },
      onDecline = {
        // Keep the old consent: plain taps still work, image steps stay off.
        showUpdatedDisclosure = false
      },
    )
  }

  details?.let { script ->
    ScriptDetailsDialog(
      script = script,
      onLoad = {
        details = null
        openPanel(script)
      },
      onDismiss = { details = null },
    )
  }

  if (showPrivacy) {
    PrivacyPolicyDialog(onDismiss = { showPrivacy = false })
  }

  if (showDisclosure) {
    AccessibilityDisclosure(
      updated = false,
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
private fun AccessibilityDisclosure(updated: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text(if (updated) "Updated: Accessibility Service use" else "Accessibility Service use") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (updated) {
          Text(
            "TapFlow can now find images on screen. This adds screenshots to what it does, so it needs your OK again. " +
              "If you say no, taps keep working and image steps stay off.",
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text("TapFlow uses Android's Accessibility Service API to perform taps, long presses and swipes at the screen positions you set, when you press Play.")
        Text("• For image steps only, it takes screenshots to find the images you picked. They are checked on your device and never saved or sent.")
        Text("• It does not read the text or content of other apps.")
        Text("• It does not collect, store remotely or share any personal data.")
        Text("• Your scripts are saved only on this device.")
        if (!updated) {
          Text("Without this permission TapFlow cannot click for you. On the next screen, find TapFlow and turn it on.")
        }
      }
    },
    confirmButton = { Button(onClick = onAccept) { Text(if (updated) "Agree" else "Agree and continue") } },
    dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
  )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked = checked, onCheckedChange = onChange)
  }
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
