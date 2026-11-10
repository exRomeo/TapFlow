package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.TextView
import dev.tapflow.autoclicker.engine.dispatchAndAwait
import dev.tapflow.autoclicker.engine.toGesture
import dev.tapflow.autoclicker.model.ActionType
import dev.tapflow.autoclicker.model.ClickTarget
import dev.tapflow.autoclicker.model.Pt
import dev.tapflow.autoclicker.service.ClickerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Records taps, long presses and swipes with a full-screen overlay. The overlay swallows
 * each touch, so after every gesture it steps aside and replays that gesture to the app
 * underneath. The app reacts as usual and the user can keep going.
 */
class GestureRecorder(
  private val service: ClickerService,
  private val wm: WindowManager,
  private val ctx: Context,
  private val scope: CoroutineScope,
  private val onFinished: (List<ClickTarget>) -> Unit,
) {
  private val density = service.resources.displayMetrics.density
  private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

  private var root: FrameLayout? = null
  private var params: WLP? = null
  private var status: TextView? = null

  private val recorded = mutableListOf<ClickTarget>()
  private var downAt = 0L
  private var lastGestureEndedAt = 0L
  private var startX = 0f
  private var startY = 0f
  private var maxDistance = 0f
  private val path = mutableListOf<Pt>()
  private var tracking = false
  private var replaying = false

  val isRecording: Boolean get() = root != null

  private fun dp(v: Int) = (v * density).roundToInt()

  @SuppressLint("ClickableViewAccessibility")
  fun start() {
    if (root != null) return
    recorded.clear()

    val frame = FrameLayout(ctx).apply {
      setBackgroundColor(0x26E53935)
      setOnTouchListener { _, e -> onTouch(e); true }
    }
    val bar = TextView(ctx).apply {
      textSize = 15f
      setTextColor(Color.WHITE)
      setPadding(dp(18), dp(10), dp(18), dp(10))
      background = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(0xE6202124.toInt())
        setStroke(dp(1), 0x66FFFFFF)
      }
      contentDescription = "Stop recording"
      setOnClickListener { stop() }
    }
    frame.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
      .apply { topMargin = dp(56) })
    status = bar
    updateStatus()

    val lp = WLP(
      MATCH_PARENT, MATCH_PARENT, WLP.TYPE_ACCESSIBILITY_OVERLAY,
      WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    wm.addView(frame, lp)
    root = frame
    params = lp
    lastGestureEndedAt = SystemClock.uptimeMillis()
  }

  fun stop() {
    val view = root ?: return
    runCatching { wm.removeView(view) }
    root = null
    params = null
    status = null
    // The last gesture has no "next" one to measure the pause against.
    if (recorded.isNotEmpty()) {
      recorded[recorded.lastIndex] = recorded.last().copy(delayAfterMs = DEFAULT_LAST_DELAY_MS)
    }
    onFinished(recorded.toList())
  }

  private fun updateStatus() {
    val n = recorded.size
    status?.text = "● Recording · $n gesture${if (n == 1) "" else "s"} · tap here to stop"
  }

  private fun onTouch(e: MotionEvent) {
    if (replaying) return
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        tracking = true
        downAt = e.eventTime
        startX = e.rawX
        startY = e.rawY
        maxDistance = 0f
        path.clear()
        // The pause before this gesture becomes the previous gesture's delay.
        if (recorded.isNotEmpty()) {
          val pause = (downAt - lastGestureEndedAt).coerceAtLeast(MIN_DELAY_MS)
          recorded[recorded.lastIndex] = recorded.last().copy(delayAfterMs = pause)
        }
      }
      MotionEvent.ACTION_MOVE -> if (tracking) {
        maxDistance = maxOf(maxDistance, hypot(e.rawX - startX, e.rawY - startY))
        val last = path.lastOrNull()
        if (last == null || hypot(e.rawX - last.x, e.rawY - last.y) >= MIN_POINT_SPACING_PX) {
          path += Pt(e.rawX, e.rawY)
        }
      }
      MotionEvent.ACTION_UP -> if (tracking) {
        tracking = false
        val duration = (e.eventTime - downAt).coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
        val target = when {
          maxDistance > touchSlop -> ClickTarget(
            type = ActionType.SWIPE,
            x = startX, y = startY, endX = e.rawX, endY = e.rawY,
            pressMs = duration,
            points = thin(path.dropLastWhile { it.x == e.rawX && it.y == e.rawY }),
          )
          duration >= LONG_PRESS_MS -> ClickTarget(ActionType.LONG_PRESS, startX, startY, pressMs = duration)
          else -> ClickTarget(ActionType.TAP, startX, startY, pressMs = duration)
        }
        recorded += target
        Log.d(TAG, "recorded ${target.type} at (${startX.toInt()}, ${startY.toInt()}) for ${duration}ms")
        updateStatus()
        replay(target)
      }
      MotionEvent.ACTION_CANCEL -> tracking = false
    }
  }

  /** Keeps long recorded paths small: at most [MAX_PATH_POINTS] evenly spaced points. */
  private fun thin(points: List<Pt>): List<Pt> {
    if (points.size <= MAX_PATH_POINTS) return points
    val step = points.size.toFloat() / MAX_PATH_POINTS
    return List(MAX_PATH_POINTS) { i -> points[(i * step).toInt()] }
  }

  /** Lets the gesture through to the app below by replaying it while the overlay is untouchable. */
  private fun replay(target: ClickTarget) {
    val view = root ?: return
    val lp = params ?: return
    replaying = true
    lp.flags = lp.flags or WLP.FLAG_NOT_TOUCHABLE
    runCatching { wm.updateViewLayout(view, lp) }
    scope.launch {
      try {
        delay(UNTOUCHABLE_SETTLE_MS)
        service.dispatchAndAwait(target.toGesture())
      } finally {
        replaying = false
        lastGestureEndedAt = SystemClock.uptimeMillis()
        if (root === view) {
          lp.flags = lp.flags and WLP.FLAG_NOT_TOUCHABLE.inv()
          runCatching { wm.updateViewLayout(view, lp) }
        }
      }
    }
  }

  private companion object {
    const val TAG = "TapFlow"
    const val LONG_PRESS_MS = 500L
    const val MIN_PRESS_MS = 20L
    const val MAX_PRESS_MS = 59_000L
    const val MIN_DELAY_MS = 50L
    const val DEFAULT_LAST_DELAY_MS = 500L
    const val UNTOUCHABLE_SETTLE_MS = 80L
    const val MIN_POINT_SPACING_PX = 6f
    const val MAX_PATH_POINTS = 60
  }
}
