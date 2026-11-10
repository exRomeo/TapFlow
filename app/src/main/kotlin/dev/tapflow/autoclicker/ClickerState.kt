package dev.tapflow.autoclicker

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dev.tapflow.autoclicker.service.ClickerService
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide state shared between the accessibility service and the UI. */
object ClickerState {
  val connected = MutableStateFlow(false)
  val running = MutableStateFlow(false)
  /** Bumped whenever the script library changes, so the UI reloads it. */
  val libraryVersion = MutableStateFlow(0)

  fun isServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
      context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val me = ComponentName(context, ClickerService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
  }
}

object Prefs {
  private const val FILE = "tapflow"
  private const val KEY_DISCLOSURE_V1 = "disclosure_accepted"
  private const val KEY_DISCLOSURE_VERSION = "disclosure_version"

  /**
   * Bump this whenever the accessibility disclosure describes a new use, so users who
   * agreed to an older text are asked again (Google Play requires consent to the current use).
   * 1 = gestures only. 2 = adds screenshots for image steps.
   */
  const val DISCLOSURE_VERSION = 2

  /** First version that allows image steps (screenshots). */
  private const val IMAGE_STEPS_SINCE = 2

  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Version of the disclosure the user agreed to, or 0 if none. */
  fun acceptedDisclosureVersion(context: Context): Int {
    val p = prefs(context)
    if (p.contains(KEY_DISCLOSURE_VERSION)) return p.getInt(KEY_DISCLOSURE_VERSION, 0)
    // Version 0.1 stored a plain yes/no for the first disclosure.
    return if (p.getBoolean(KEY_DISCLOSURE_V1, false)) 1 else 0
  }

  fun hasCurrentConsent(context: Context) = acceptedDisclosureVersion(context) >= DISCLOSURE_VERSION

  fun imageStepsAllowed(context: Context) = acceptedDisclosureVersion(context) >= IMAGE_STEPS_SINCE

  fun setDisclosureAccepted(context: Context, accepted: Boolean) =
    prefs(context).edit()
      .putInt(KEY_DISCLOSURE_VERSION, if (accepted) DISCLOSURE_VERSION else 0)
      .remove(KEY_DISCLOSURE_V1)
      .apply()
}
