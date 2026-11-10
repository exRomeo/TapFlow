package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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

    // One compact bar holding the hint and both buttons. Drag it by the ≡ handle (or any
    // non-button part) to uncover whatever it is covering.
    val bar = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(6), dp(2), dp(6), dp(2))
      background = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(0xEE202124.toInt())
        setStroke(dp(1), 0x66FFFFFF)
      }
    }
    bar.addView(TextView(ctx).apply {
      text = "≡"
      textSize = 20f
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
      contentDescription = "Move this bar"
    }, LinearLayout.LayoutParams(dp(36), dp(44)))
    bar.addView(TextView(ctx).apply {
      text = "Box the image"
      textSize = 14f
      setTextColor(0xFFDDDDDD.toInt())
      setPadding(0, 0, dp(4), 0)
    })
    fun barButton(label: String) = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
      text = label
      isAllCaps = false
      setTextColor(Color.WHITE)
      minWidth = 0
      minimumWidth = 0
      setPadding(dp(10), 0, dp(10), 0)
    }
    val useButton = barButton("Use").apply {
      isEnabled = false
      alpha = 0.4f
      setOnClickListener { finish(selector.selection()) }
    }
    bar.addView(barButton("Cancel").apply { setOnClickListener { finish(null) } })
    bar.addView(useButton)
    frame.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
      .apply { bottomMargin = dp(48) })
    bar.isClickable = true
    makeBarDraggable(bar, frame)
    // Belt and braces: a drag that starts on the bar never draws a selection box.
    selector.isOnBar = { x, y ->
      val loc = IntArray(2).also(bar::getLocationOnScreen)
      x >= loc[0] && x < loc[0] + bar.width && y >= loc[1] && y < loc[1] + bar.height
    }
    selector.onSelectionChanged = {
      useButton.isEnabled = it != null
      useButton.alpha = if (it != null) 1f else 0.4f
    }

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

  /** Moves [bar] with the finger, kept inside [container]. Button presses still work. */
  @SuppressLint("ClickableViewAccessibility")
  private fun makeBarDraggable(bar: View, container: View) {
    var downX = 0f
    var downY = 0f
    var startTx = 0f
    var startTy = 0f
    bar.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY
          startTx = bar.translationX; startTy = bar.translationY
        }
        MotionEvent.ACTION_MOVE -> {
          // Clamp so the whole bar stays on screen.
          val minTx = -bar.left.toFloat()
          val maxTx = (container.width - bar.right).toFloat()
          val minTy = -bar.top.toFloat()
          val maxTy = (container.height - bar.bottom).toFloat()
          bar.translationX = (startTx + e.rawX - downX).coerceIn(minTx, maxTx)
          bar.translationY = (startTy + e.rawY - downY).coerceIn(minTy, maxTy)
        }
      }
      true
    }
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
    var isOnBar: (Float, Float) -> Boolean = { _, _ -> false }

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
      // Not consuming the down event means the rest of this gesture isn't ours either.
      if (e.actionMasked == MotionEvent.ACTION_DOWN && isOnBar(e.rawX, e.rawY)) return false
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
