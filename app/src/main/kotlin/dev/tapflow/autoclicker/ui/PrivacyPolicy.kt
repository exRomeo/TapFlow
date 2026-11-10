package dev.tapflow.autoclicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The in-app privacy policy. Keep it in sync with docs/privacy-policy.md, which is the
 * version to host publicly for the Google Play listing.
 */
private val POLICY: List<Pair<String, String>> = listOf(
  "Summary" to
    "TapFlow works entirely on your device. It has no account, no ads, no analytics and no internet access, " +
    "so nothing you do in TapFlow is sent anywhere.",
  "Accessibility Service" to
    "TapFlow uses Android's Accessibility Service API only to perform the taps, long presses and swipes you set up, " +
    "when you start a script. It does not read the text or content of other apps' windows and does not monitor what you do.",
  "Screenshots (image steps)" to
    "If you use image steps, TapFlow takes screenshots while a script with an image step is running, and once when you " +
    "capture an image. Screenshots are compared with your images in memory on the device and then discarded. " +
    "They are never saved or sent.",
  "What is stored" to
    "Your scripts (target positions, timings and settings) and the small images you cut out for image steps are saved " +
    "in TapFlow's private storage on your device. Scripts may be included in your Android device backup; the images are not. " +
    "Uninstalling TapFlow, or clearing its storage, deletes all of it.",
  "Sharing" to
    "TapFlow does not share, sell or transfer any data to anyone.",
  "Changes" to
    "If TapFlow ever starts using your data in a new way, it will ask for your agreement again first.",
)

const val PRIVACY_POLICY_UPDATED = "10 November 2026"

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Privacy policy") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Last updated $PRIVACY_POLICY_UPDATED", style = MaterialTheme.typography.bodySmall)
        POLICY.forEach { (heading, body) ->
          Text(heading, fontWeight = FontWeight.SemiBold)
          Text(body, style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
