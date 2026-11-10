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
  /** True while a run is waiting for its scheduled start time; nothing is tapped yet. */
  val waitingForSchedule = MutableStateFlow(false)
  /** A run was paused part-way; ▶ resumes it from the same step. */
  val paused = MutableStateFlow(false)
  /**
   * Index of the step most recently started this round, [ALL_STEPS] in Sync mode, or
   * [NO_STEP] before the first one.
   */
  val currentStep = MutableStateFlow(NO_STEP)
  /** True only while a gesture is actually being performed (finger "down"). */
  val gestureActive = MutableStateFlow(false)
  const val NO_STEP = -1
  const val ALL_STEPS = -2


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

  private const val KEY_PANEL_SIZE = "panel_size"
  private const val KEY_PANEL_OPACITY = "panel_opacity"
  private const val KEY_MARKER_SIZE = "marker_size"
  private const val KEY_MARKER_OPACITY = "marker_opacity"
  private const val KEY_PANEL_ORIENTATION = "panel_orientation"
  private const val KEY_COMPACT_WHILE_RUNNING = "compact_while_running"

  /** Panel size in percent of the default (70..150). */
  fun panelSize(context: Context) = prefs(context).getInt(KEY_PANEL_SIZE, 100)
  fun setPanelSize(context: Context, value: Int) = prefs(context).edit().putInt(KEY_PANEL_SIZE, value).apply()

  /** Panel opacity in percent (30..100). */
  fun panelOpacity(context: Context) = prefs(context).getInt(KEY_PANEL_OPACITY, 100)
  fun setPanelOpacity(context: Context, value: Int) = prefs(context).edit().putInt(KEY_PANEL_OPACITY, value).apply()

  /** Marker size in percent of the default (60..160). */
  fun markerSize(context: Context) = prefs(context).getInt(KEY_MARKER_SIZE, 100)
  fun setMarkerSize(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MARKER_SIZE, value).apply()

  /** Marker opacity in percent (30..100). */
  fun markerOpacity(context: Context) = prefs(context).getInt(KEY_MARKER_OPACITY, 100)
  fun setMarkerOpacity(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MARKER_OPACITY, value).apply()

  /** "auto", "vertical" or "horizontal". */
  fun panelOrientation(context: Context) = prefs(context).getString(KEY_PANEL_ORIENTATION, "auto") ?: "auto"
  fun setPanelOrientation(context: Context, value: String) =
    prefs(context).edit().putString(KEY_PANEL_ORIENTATION, value).apply()

  /** While a script runs, shrink the panel to just the pause button. */
  fun compactWhileRunning(context: Context) = prefs(context).getBoolean(KEY_COMPACT_WHILE_RUNNING, true)
  fun setCompactWhileRunning(context: Context, value: Boolean) =
    prefs(context).edit().putBoolean(KEY_COMPACT_WHILE_RUNNING, value).apply()

  private const val KEY_STOP_ON_TOUCH = "stop_on_touch"
  private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
  private const val KEY_WAS_CONNECTED = "was_connected"
  private const val KEY_OEM_GUIDE_DONE = "oem_guide_done"

  /** Stop a running script when the user touches the screen. */
  fun stopOnTouch(context: Context) = prefs(context).getBoolean(KEY_STOP_ON_TOUCH, true)
  fun setStopOnTouch(context: Context, value: Boolean) =
    prefs(context).edit().putBoolean(KEY_STOP_ON_TOUCH, value).apply()

  /** Keep the screen on while a script runs or waits for its start time. */
  fun keepScreenOn(context: Context) = prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)
  fun setKeepScreenOn(context: Context, value: Boolean) =
    prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

  /** The service has connected at least once, so "off" now means something switched it off. */
  fun wasConnected(context: Context) = prefs(context).getBoolean(KEY_WAS_CONNECTED, false)
  fun setWasConnected(context: Context) = prefs(context).edit().putBoolean(KEY_WAS_CONNECTED, true).apply()

  /** The user said they finished the phone-maker setup steps. */
  fun oemGuideDone(context: Context) = prefs(context).getBoolean(KEY_OEM_GUIDE_DONE, false)
  fun setOemGuideDone(context: Context, value: Boolean) =
    prefs(context).edit().putBoolean(KEY_OEM_GUIDE_DONE, value).apply()

  fun setDisclosureAccepted(context: Context, accepted: Boolean) =
    prefs(context).edit()
      .putInt(KEY_DISCLOSURE_VERSION, if (accepted) DISCLOSURE_VERSION else 0)
      .remove(KEY_DISCLOSURE_V1)
      .apply()
}
