package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows a frozen screenshot full screen and lets the user drag a box around the image
 * an image step should look for. [onDone] gets the box in screen pixels, or null on cancel.
 */
class RegionSelectOverlay(
  private val wm: WindowManager,
  private val ctx: Context,
  private val screenshot: Bitmap,
  private val onDone: (Rect?) -> Unit,
) {
  private val density = ctx.resources.displayMetrics.density
  private var root: View? = null

  private fun dp(v: Int) = (v * density).roundToInt()

  fun show() {
    val frame = FrameLayout(ctx)
    val selector = SelectorView(ctx)
    frame.addView(selector, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    val hint = TextView(ctx).apply {
      text = "Drag a box around the image to look for"
      setTextColor(Color.WHITE)
      textSize = 15f
      setPadding(dp(16), dp(10), dp(16), dp(10))
      setBackgroundColor(0xCC202124.toInt())
    }
    frame.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
      .apply { topMargin = dp(56) })

    val bar = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      setBackgroundColor(0xE6FFFFFF.toInt())
      setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    val useButton = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
      text = "Use selection"
      isEnabled = false
      setOnClickListener { finish(selector.selection()) }
    }
    bar.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
      text = "Cancel"
      setOnClickListener { finish(null) }
    })
    bar.addView(useButton)
    frame.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
      .apply { bottomMargin = dp(48) })
    selector.onSelectionChanged = { useButton.isEnabled = it != null }

    val lp = WLP(
      MATCH_PARENT, MATCH_PARENT, WLP.TYPE_ACCESSIBILITY_OVERLAY,
      WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.OPAQUE,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    wm.addView(frame, lp)
    root = frame
  }

  private fun finish(rect: Rect?) {
    root?.let { runCatching { wm.removeView(it) } }
    root = null
    onDone(rect)
  }

  /** Draws the screenshot at its true screen position and the box being dragged. */
  @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
  private inner class SelectorView(context: Context) : View(context) {
    private val loc = IntArray(2)
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var hasBox = false
    var onSelectionChanged: (Rect?) -> Unit = {}

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2.5f * density
      color = 0xFFFFEB3B.toInt()
    }

    /** The box in screen pixels, clamped to the screenshot; null if too small. */
    fun selection(): Rect? {
      if (!hasBox) return null
      val r = Rect(
        minOf(startX, endX).roundToInt(), minOf(startY, endY).roundToInt(),
        maxOf(startX, endX).roundToInt(), maxOf(startY, endY).roundToInt(),
      )
      if (!r.intersect(0, 0, screenshot.width, screenshot.height)) return null
      return r.takeIf { it.width() >= MIN_SIZE_PX && it.height() >= MIN_SIZE_PX }
    }

    override fun onDraw(canvas: Canvas) {
      getLocationOnScreen(loc)
      // Draw in screen coordinates so the frozen image lines up with the real screen.
      canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
      canvas.drawBitmap(screenshot, 0f, 0f, null)
      val box = selection()
      if (box == null) {
        canvas.drawRect(0f, 0f, screenshot.width.toFloat(), screenshot.height.toFloat(), dim)
        return
      }
      val w = screenshot.width.toFloat()
      val h = screenshot.height.toFloat()
      canvas.drawRect(0f, 0f, w, box.top.toFloat(), dim)
      canvas.drawRect(0f, box.bottom.toFloat(), w, h, dim)
      canvas.drawRect(0f, box.top.toFloat(), box.left.toFloat(), box.bottom.toFloat(), dim)
      canvas.drawRect(box.right.toFloat(), box.top.toFloat(), w, box.bottom.toFloat(), dim)
      canvas.drawRect(box, outline)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startX = e.rawX; startY = e.rawY
          endX = e.rawX; endY = e.rawY
          hasBox = true
        }
        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
          endX = e.rawX; endY = e.rawY
        }
      }
      invalidate()
      onSelectionChanged(selection())
      return true
    }
  }

  private companion object {
    const val MIN_SIZE_PX = 24
  }
}
