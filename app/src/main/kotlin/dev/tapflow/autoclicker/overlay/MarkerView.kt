package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import dev.tapflow.autoclicker.model.ActionType

/** Where a step stands in the current round, shown as a traffic-light ring. */
enum class StepState(val ring: Int) {
  /** Not running, or later in the round: white. */
  IDLE(Color.WHITE),
  /** Already played this round: red. */
  DONE(0xFFF44336.toInt()),
  /** Its gesture is being performed right now: green. */
  CURRENT(0xFF4CAF50.toInt()),
  /** Plays next: yellow. */
  NEXT(0xFFFFEB3B.toInt()),
}

/** Numbered circle that marks where a tap lands. Swipe end points are drawn hollow. */
@SuppressLint("ViewConstructor")
class MarkerView(
  context: Context,
  private val label: String,
  type: ActionType,
  private val isEnd: Boolean,
) : View(context) {
  private val density = resources.displayMetrics.density

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = colorFor(type)
    alpha = if (isEnd) 90 else 210
  }
  private val ringWidth = 2.5f * density
  private val activeRingWidth = 4.5f * density
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = ringWidth
    color = Color.WHITE
  }

  /** Traffic-light state of this marker's step. */
  var state = StepState.IDLE
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textAlign = Paint.Align.CENTER
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    typeface = Typeface.DEFAULT_BOLD
    setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
  }

  init {
    contentDescription = "Target $label"
  }

  override fun onDraw(canvas: Canvas) {
    val cx = width / 2f
    val cy = height / 2f
    val r = minOf(width, height) / 2f - activeRingWidth
    ring.strokeWidth = if (state == StepState.IDLE) ringWidth else activeRingWidth
    ring.color = state.ring
    canvas.drawCircle(cx, cy, r, fill)
    canvas.drawCircle(cx, cy, r, ring)
    canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
  }

  companion object {

    fun colorFor(type: ActionType): Int = when (type) {
      ActionType.TAP -> 0xFF1E88E5.toInt()
      ActionType.LONG_PRESS -> 0xFFFB8C00.toInt()
      ActionType.SWIPE -> 0xFF43A047.toInt()
      ActionType.IMAGE -> 0xFF8E24AA.toInt()
    }
  }
}
