package dev.tapflow.autoclicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tapflow.autoclicker.Prefs
import dev.tapflow.autoclicker.model.PanelMode
import dev.tapflow.autoclicker.model.Script
import dev.tapflow.autoclicker.model.describeScript
import dev.tapflow.autoclicker.model.describeStep
import dev.tapflow.autoclicker.model.stepTitle
import dev.tapflow.autoclicker.service.ClickerService
import kotlin.math.roundToInt

/** Two-column grid of panel modes, like choosing a mode in other auto clickers. */
@Composable
fun ModePicker(onPick: (PanelMode) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PanelMode.entries.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { mode ->
          Card(onClick = { onPick(mode) }, modifier = Modifier.weight(1f).heightIn(min = 112.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(mode.icon, fontSize = 24.sp)
              Text(mode.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
              Text(mode.description, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

/** Size, opacity and orientation of the floating panel and the markers. */
@Composable
fun AppearanceSettings() {
  val context = LocalContext.current
  fun apply() = ClickerService.instance?.applyAppearance()

  PercentSlider("Panel size", Prefs.panelSize(context), 70..150, { Prefs.setPanelSize(context, it) }, ::apply)
  PercentSlider("Panel opacity", Prefs.panelOpacity(context), 30..100, { Prefs.setPanelOpacity(context, it) }, ::apply)
  PercentSlider("Marker size", Prefs.markerSize(context), 60..160, { Prefs.setMarkerSize(context, it) }, ::apply)
  PercentSlider("Marker opacity", Prefs.markerOpacity(context), 30..100, { Prefs.setMarkerOpacity(context, it) }, ::apply)

  Text("Panel orientation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
  var orientation by remember { mutableStateOf(Prefs.panelOrientation(context)) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf("auto" to "Auto", "vertical" to "Vertical", "horizontal" to "Horizontal").forEach { (value, label) ->
      FilterChip(
        selected = orientation == value,
        onClick = {
          orientation = value
          Prefs.setPanelOrientation(context, value)
          apply()
        },
        label = { Text(label) },
      )
    }
  }

  var compact by remember { mutableStateOf(Prefs.compactWhileRunning(context)) }
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      "Shrink the panel to just ⏸ while running",
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
    )
    Switch(checked = compact, onCheckedChange = {
      compact = it
      Prefs.setCompactWhileRunning(context, it)
      apply()
    })
  }
}

/**
 * A 10 %-step slider. Every change is saved right away, so the stored value always matches
 * the label; the (slower) panel rebuild happens once, when the finger lifts.
 */
@Composable
private fun PercentSlider(label: String, initial: Int, range: IntRange, save: (Int) -> Unit, onDone: () -> Unit) {
  var value by remember { mutableFloatStateOf(initial.toFloat()) }
  Text("$label: ${value.roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
  Slider(
    value = value,
    onValueChange = {
      value = it
      save(it.roundToInt())
    },
    onValueChangeFinished = onDone,
    valueRange = range.first.toFloat()..range.last.toFloat(),
    steps = (range.last - range.first) / 10 - 1,
  )
}

/** Everything a saved script will do, step by step. */
@Composable
fun ScriptDetailsDialog(script: Script, onLoad: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(script.name) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        describeScript(script).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        if (script.targets.isEmpty()) Text("No steps.")
        script.targets.forEachIndexed { i, t ->
          Column {
            Text(stepTitle(i, t), fontWeight = FontWeight.SemiBold)
            Text(describeStep(t), style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    },
    confirmButton = { Button(onClick = onLoad) { Text("Load") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

/** One-line summary for a saved script's card. */
fun scriptSummary(s: Script): String {
  val steps = "${s.targets.size} step${if (s.targets.size == 1) "" else "s"}"
  val repeats = if (s.loops == 0) "repeats forever" else "${s.loops}×"
  return "${s.panelMode.icon} ${s.panelMode.title} · $steps · $repeats"
}
