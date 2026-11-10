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
  private const val KEY_DISCLOSURE = "disclosure_accepted"

  fun disclosureAccepted(context: Context) =
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DISCLOSURE, false)

  fun setDisclosureAccepted(context: Context, value: Boolean) =
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DISCLOSURE, value).apply()
}
