package dev.tapflow.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.data.ScriptStore
import dev.tapflow.autoclicker.data.TemplateStore
import dev.tapflow.autoclicker.engine.ScriptRunner
import dev.tapflow.autoclicker.model.Script
import dev.tapflow.autoclicker.model.formatMinute
import dev.tapflow.autoclicker.overlay.OverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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

  /** The script currently shown on screen. */
  var script: Script = Script()

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    store = ScriptStore(this)
    templates = TemplateStore(this)
    runner = ScriptRunner(this, scope, templates)
    overlay = OverlayManager(this, scope).also { it.showPanel() }
    ClickerState.connected.value = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
    overlay?.hideAll()
  }

  fun toggleRun() {
    val r = runner ?: return
    when {
      r.isRunning -> r.stop()
      script.targets.isEmpty() -> toast("Add a target first")
      else -> {
        r.start(script)
        if (script.startAtMinute >= 0) {
          toast("Waiting until ${formatMinute(script.startAtMinute)}. Keep the screen on. Press ■ to cancel.")
        }
      }
    }
  }

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

  /** Shows the floating panel, optionally replacing the current script. */
  fun openPanel(newScript: Script? = null) {
    runner?.stop()
    if (newScript != null) script = newScript
    overlay?.showPanel()
  }

  fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

  companion object {
    var instance: ClickerService? = null
      private set
  }
}
