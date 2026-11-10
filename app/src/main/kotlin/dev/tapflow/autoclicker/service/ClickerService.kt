package dev.tapflow.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.Prefs
import dev.tapflow.autoclicker.data.ScriptStore
import dev.tapflow.autoclicker.data.TemplateStore
import dev.tapflow.autoclicker.engine.ScriptRunner
import dev.tapflow.autoclicker.model.PanelMode
import dev.tapflow.autoclicker.model.Script
import dev.tapflow.autoclicker.model.formatMinute
import dev.tapflow.autoclicker.overlay.OverlayManager
import dev.tapflow.autoclicker.overlay.TouchWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Performs the taps. It only dispatches gestures the user set up and started, and takes
 * screenshots only to find images for image steps. It does not read window content or
 * listen to accessibility events.
 */
class ClickerService : AccessibilityService() {
  private val scope = MainScope()
  private var overlay: OverlayManager? = null
  private var runner: ScriptRunner? = null
  private lateinit var store: ScriptStore
  lateinit var templates: TemplateStore
    private set
  private var touchWatcher: TouchWatcher? = null

  /** Stops a playing script when the screen turns off (power button, timeout). */
  private val screenOffReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      // A run still waiting for its start time keeps waiting; nothing is being tapped yet.
      if (!ClickerState.waitingForSchedule.value) runner?.stop("the screen turned off")
    }
  }

  /** The script currently shown on screen. */
  var script: Script = Script()

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    store = ScriptStore(this)
    templates = TemplateStore(this)
    runner = ScriptRunner(this, scope, templates) { reason ->
      when {
        reason != null && ClickerState.paused.value -> toast("TapFlow paused: $reason. ▶ resumes.")
        reason != null -> toast("TapFlow stopped: $reason")
      }
    }
    touchWatcher = TouchWatcher(
      getSystemService(WindowManager::class.java), this,
      isOwnUi = { x, y -> overlay?.isOverPanel(x, y) == true },
    ) {
      runner?.stop("you touched the screen")
    }
    ContextCompat.registerReceiver(
      this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    // Watch for user touches only while gestures are actually playing.
    scope.launch {
      combine(ClickerState.running, ClickerState.waitingForSchedule) { running, waiting -> running && !waiting }
        .collect { playing ->
          if (playing && Prefs.stopOnTouch(this@ClickerService)) touchWatcher?.start() else touchWatcher?.stop()
        }
    }
    // The panel opens only when the user asks for it in the app ("Open floating panel" or
    // "Load"), not whenever the system (re)connects the service: after enabling it, a reboot,
    // or an app update.
    overlay = OverlayManager(this, scope)
    ClickerState.connected.value = true
    Prefs.setWasConnected(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // After a rotation the panel's old position may be off screen; wait for the new size.
    scope.launch {
      delay(300)
      overlay?.keepPanelOnScreen()
    }
  }

  override fun onInterrupt() {
    runner?.stop()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    teardown()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    teardown()
    scope.cancel()
    super.onDestroy()
  }

  private fun teardown() {
    if (instance === this) instance = null
    ClickerState.connected.value = false
    runner?.stop()
    touchWatcher?.stop()
    overlay?.hideAll()
    runCatching { unregisterReceiver(screenOffReceiver) }
  }

  /** ▶ starts or resumes, ⏸ pauses. */
  fun toggleRun() {
    val r = runner ?: return
    when {
      r.isRunning -> {
        r.stop()
        if (ClickerState.paused.value) toast("Paused. ▶ resumes, ⟲ starts over.")
      }
      script.targets.isEmpty() -> toast("Add a target first")
      else -> {
        val resuming = ClickerState.paused.value
        r.start(script)
        if (!resuming && script.startAtMinute >= 0) {
          toast("Waiting until ${formatMinute(script.startAtMinute)}. Press ⏸ to cancel.")
        }
      }
    }
  }

  /** Forgets a paused position and starts again from step 1. */
  fun restartRun() {
    val r = runner ?: return
    r.stop()
    r.clearPause()
    if (script.targets.isNotEmpty()) r.start(script)
  }

  /** Pauses a running script (used before editing it). */
  fun stopRun() {
    runner?.stop()
  }

  fun saveScript() {
    scope.launch {
      script = withContext(Dispatchers.IO) { store.save(script) }
      ClickerState.libraryVersion.value++
      toast("Saved \"${script.name}\"")
    }
  }

  /**
   * Shows the floating panel. With [newScript] it loads that script (in its own mode);
   * with [mode] it switches to that mode, starting a fresh script if the mode changes.
   */
  fun openPanel(newScript: Script? = null, mode: PanelMode? = null) {
    runner?.stop()
    runner?.clearPause()
    when {
      newScript != null -> script = newScript
      mode != null && script.panelMode != mode -> script = Script(panelMode = mode)
    }
    overlay?.showPanel()
  }

  /** Re-applies the panel and marker look after the user changes it in the app. */
  fun applyAppearance() {
    overlay?.refreshAppearance()
  }

  fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

  companion object {
    var instance: ClickerService? = null
      private set
  }
}
