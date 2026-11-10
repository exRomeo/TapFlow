package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP

/**
 * Notices when the user touches the screen while a script runs. A 1×1 px window that
 * watches outside touches gets ACTION_OUTSIDE for every touch-down anywhere on screen.
 * Our own injected gestures (unknown tool type), and presses on our own panel (the ⏸
 * button handles those), are ignored. Outside touches on windows of the same app
 * carry real coordinates, which is what makes the panel check possible.
 */
class TouchWatcher(
  private val wm: WindowManager,
  private val context: Context,
  /** True for touches on TapFlow's own panel, which are button presses, not "touching the screen". */
  private val isOwnUi: (x: Float, y: Float) -> Boolean,
  private val onUserTouch: () -> Unit,
) {
  private var view: View? = null

  @SuppressLint("ClickableViewAccessibility")
  fun start() {
    if (view != null) return
    val v = View(context).apply {
      setOnTouchListener { _, e ->
        // Gestures injected by an accessibility service arrive with an unknown tool type;
        // a real finger, stylus or mouse has a known one. (Timing can't tell them apart: on
        // fast scripts a real touch often lands right next to one of ours.)
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE &&
          e.getToolType(0) != MotionEvent.TOOL_TYPE_UNKNOWN &&
          !isOwnUi(e.rawX, e.rawY)
        ) {
          onUserTouch()
        }
        false
      }
    }
    val lp = WLP(
      1, 1, WLP.TYPE_ACCESSIBILITY_OVERLAY,
      WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCH_MODAL or WLP.FLAG_WATCH_OUTSIDE_TOUCH,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    runCatching { wm.addView(v, lp) }.onSuccess { view = v }
  }

  fun stop() {
    view?.let { runCatching { wm.removeView(it) } }
    view = null
  }
}
