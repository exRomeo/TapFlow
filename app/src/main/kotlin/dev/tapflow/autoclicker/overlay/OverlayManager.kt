package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.Prefs
import dev.tapflow.autoclicker.engine.captureScreen
import dev.tapflow.autoclicker.engine.imageStepsSupported
import dev.tapflow.autoclicker.model.ActionType
import dev.tapflow.autoclicker.model.ClickTarget
import dev.tapflow.autoclicker.model.Pt
import dev.tapflow.autoclicker.model.RunMode
import dev.tapflow.autoclicker.model.Script
import dev.tapflow.autoclicker.model.formatMinute
import dev.tapflow.autoclicker.model.parseMinute
import dev.tapflow.autoclicker.service.ClickerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns every overlay window: the floating control panel, one window per target marker,
 * and the edit forms. All windows are TYPE_ACCESSIBILITY_OVERLAY, which needs no
 * "display over other apps" permission and lets injected taps pass through markers
 * once they are made untouchable.
 */
class OverlayManager(private val service: ClickerService, private val scope: CoroutineScope) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val ctx = ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault_Light_Dialog)
  private val density = service.resources.displayMetrics.density
  private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

  private var panel: View? = null
  private var playButton: TextView? = null
  private val markers = mutableListOf<View>()
  private var form: View? = null
  private val recorder = GestureRecorder(service, wm, ctx, scope, ::onRecordingFinished)

  /** Difference between requested window position and where the window really lands. */
  private var calibX = 0
  private var calibY = 0
  private var calibrated = false

  private var script: Script
    get() = service.script
    set(value) { service.script = value }

  init {
    scope.launch { ClickerState.running.collect(::onRunningChanged) }
  }

  private fun dp(v: Int) = (v * density).roundToInt()

  private fun overlayParams(w: Int, h: Int) = WLP(
    w, h, WLP.TYPE_ACCESSIBILITY_OVERLAY,
    WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
  }

  // ---------------------------------------------------------------- panel

  fun showPanel() {
    if (panel == null) addPanel()
    rebuildMarkers()
  }

  fun hideAll() {
    service.stopRun()
    if (recorder.isRecording) recorder.stop()
    closeForm()
    removeMarkers()
    panel?.let { runCatching { wm.removeView(it) } }
    panel = null
    playButton = null
  }

  private fun addPanel() {
    val root = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(4), dp(6), dp(4), dp(6))
      background = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(0xE6202124.toInt())
        // Light outline keeps the panel visible on dark screens.
        setStroke(dp(1), 0x66FFFFFF)
      }
    }

    fun button(glyph: String, description: String, color: Int = Color.WHITE, onClick: () -> Unit) = TextView(ctx).apply {
      text = glyph
      textSize = 20f
      setTextColor(color)
      gravity = Gravity.CENTER
      contentDescription = description
      layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
      setOnClickListener { onClick() }
    }.also(root::addView)

    val handle = button("≡", "Move panel") {}
    playButton = button("▶", "Start or stop") { service.toggleRun() }
    button("+", "Add tap target") { addTarget(ActionType.TAP) }
    button("↗", "Add swipe target") { addTarget(ActionType.SWIPE) }
    button("●", "Record gestures", color = 0xFFFF5252.toInt()) { startRecording() }
    button("🖼", "Add image step") { captureImage(appendTo = null) }
    button("−", "Remove last target") { removeTarget(script.targets.lastIndex) }
    button("⚙", "Script settings") { showScriptSettings() }
    button("💾", "Save script") { service.saveScript() }
    button("✕", "Close panel") { hideAll() }

    val params = overlayParams(WRAP_CONTENT, WRAP_CONTENT).apply {
      x = dp(8)
      y = dp(160)
    }
    wm.addView(root, params)
    makeDraggable(handle, root, params, onTap = {}, onDrop = {})
    panel = root
    onRunningChanged(ClickerState.running.value)
  }

  private fun onRunningChanged(running: Boolean) {
    playButton?.text = if (running) "■" else "▶"
    if (running) closeForm()
    setMarkersTouchable(!running)
  }

  // ------------------------------------------------------------ recording

  private fun startRecording() {
    service.stopRun()
    closeForm()
    // Panel and markers must not catch the gestures the recorder replays.
    removeMarkers()
    panel?.visibility = View.GONE
    recorder.start()
  }

  private fun onRecordingFinished(recorded: List<ClickTarget>) {
    panel?.visibility = View.VISIBLE
    if (recorded.isNotEmpty()) {
      script = script.copy(targets = script.targets + recorded, mode = RunMode.SEQUENCE)
      service.toast("Recorded ${recorded.size} gesture${if (recorded.size == 1) "" else "s"}")
    }
    rebuildMarkers()
  }

  // --------------------------------------------------------- image steps

  /**
   * Hides TapFlow's own windows, takes a screenshot and lets the user box an image.
   * With [appendTo] null it adds a new image step; otherwise it adds the image to that step.
   */
  private fun captureImage(appendTo: Int?) {
    if (!imageStepsSupported) {
      service.toast("Image steps need Android 11 or newer")
      return
    }
    if (!Prefs.imageStepsAllowed(service)) {
      service.toast("Open TapFlow and agree to the updated permission explanation to use image steps")
      return
    }
    service.stopRun()
    closeForm()
    removeMarkers()
    panel?.visibility = View.GONE
    scope.launch {
      delay(CAPTURE_SETTLE_MS) // let our windows disappear from the screen first
      val shot = service.captureScreen()
      if (shot == null) {
        service.toast("Couldn't take a screenshot")
        restoreAfterCapture(appendTo)
        return@launch
      }
      RegionSelectOverlay(wm, ctx, shot) { box ->
        if (box != null) {
          val crop = Bitmap.createBitmap(shot, box.left, box.top, box.width(), box.height())
          val name = service.templates.save(crop)
          script = if (appendTo == null) {
            // Tapping an image usually changes the screen; wait for that before the next search.
            val step = ClickTarget(ActionType.IMAGE, box.exactCenterX(), box.exactCenterY(),
              delayAfterMs = 1000, imageFiles = listOf(name))
            script.copy(targets = script.targets + step, mode = RunMode.SEQUENCE)
          } else {
            val t = script.targets[appendTo]
            script.copy(targets = script.targets.toMutableList().also {
              it[appendTo] = t.copy(imageFiles = t.imageFiles + name)
            })
          }
        }
        shot.recycle()
        restoreAfterCapture(appendTo)
      }.show()
    }
  }

  private fun restoreAfterCapture(reopenEditorFor: Int?) {
    panel?.visibility = View.VISIBLE
    rebuildMarkers()
    if (reopenEditorFor != null) showTargetEditor(reopenEditorFor)
  }

  private fun showImageStepEditor(index: Int) {
    val t = script.targets.getOrNull(index) ?: return
    val body = formBody()
    label(body, "Looks for any of these images (tap one to remove it)")
    val thumbs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    t.imageFiles.forEach { name ->
      thumbs.addView(ImageView(ctx).apply {
        setImageBitmap(service.templates.load(name))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFFEEEEEE.toInt())
        contentDescription = "Remove image"
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(6) }
        setOnClickListener {
          val current = script.targets.getOrNull(index) ?: return@setOnClickListener
          if (current.imageFiles.size <= 1) {
            service.toast("A step needs at least one image. Delete the step instead.")
            return@setOnClickListener
          }
          updateTarget(index, current.copy(imageFiles = current.imageFiles - name))
          showImageStepEditor(index)
        }
      })
    }
    body.addView(HorizontalScrollView(ctx).apply { addView(thumbs) })
    body.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
      text = "+ Add another image"
      setOnClickListener { captureImage(appendTo = index) }
    })
    val threshold = numberField(body, "Match needed (%)", (t.matchThreshold * 100).roundToInt().toLong())
    val timeout = numberField(body, "Keep looking for (seconds)", t.waitTimeoutMs / 1000)
    label(body, "If not found")
    val notFound = radioGroup(body, listOf("Skip this step", "Stop the script"), if (t.stopIfNotFound) 1 else 0)
    val delay = numberField(body, "Delay after (ms)", t.delayAfterMs)
    val randomOffset = numberField(body, "Random position offset (px)", t.randomOffsetPx.toLong())

    showForm("Image step ${index + 1}", body, listOf(
      "Delete" to { removeTarget(index) },
      "Cancel" to {},
      "Save" to save@{
        val current = script.targets.getOrNull(index) ?: return@save
        updateTarget(index, current.copy(
          matchThreshold = threshold.longOr(85).coerceIn(50, 100) / 100f,
          waitTimeoutMs = timeout.longOr(current.waitTimeoutMs / 1000).coerceIn(1, 3600) * 1000,
          stopIfNotFound = notFound.checkedIndex() == 1,
          delayAfterMs = delay.longOr(current.delayAfterMs).coerceAtLeast(0),
          randomOffsetPx = randomOffset.longOr(current.randomOffsetPx.toLong()).toInt().coerceIn(0, 500),
        ))
        rebuildMarkers()
      },
    ))
  }

  private fun updateTarget(index: Int, target: ClickTarget) {
    if (index !in script.targets.indices) return
    script = script.copy(targets = script.targets.toMutableList().also { it[index] = target })
  }

  // -------------------------------------------------------------- markers

  private fun addTarget(type: ActionType) {
    service.stopRun()
    val dm = service.resources.displayMetrics
    val n = script.targets.size
    val cx = dm.widthPixels / 2f + (n % 4 - 1.5f) * dp(56)
    val cy = dm.heightPixels / 2f + (n / 4 % 4 - 1.5f) * dp(56)
    val target = if (type == ActionType.SWIPE) {
      ClickTarget(type, cx, cy + dp(110), endX = cx, endY = cy - dp(110))
    } else {
      ClickTarget(type, cx, cy)
    }
    script = script.copy(targets = script.targets + target)
    rebuildMarkers()
  }

  private fun removeTarget(index: Int) {
    if (index !in script.targets.indices) return
    service.stopRun()
    script = script.copy(targets = script.targets.filterIndexed { i, _ -> i != index })
    rebuildMarkers()
  }

  private fun removeMarkers() {
    markers.forEach { runCatching { wm.removeView(it) } }
    markers.clear()
  }

  private fun rebuildMarkers() {
    removeMarkers()
    if (panel == null) return
    script.targets.forEachIndexed { i, t ->
      // Add the end point first so the start marker is drawn on top of it.
      if (t.type == ActionType.SWIPE) addMarker(i, t, isEnd = true)
      addMarker(i, t, isEnd = false)
    }
    setMarkersTouchable(!ClickerState.running.value)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun addMarker(index: Int, target: ClickTarget, isEnd: Boolean) {
    val size = dp(MARKER_DP)
    val label = if (isEnd) "${index + 1}'" else "${index + 1}"
    val view = MarkerView(ctx, label, target.type, isEnd)
    val cx = if (isEnd) target.endX else target.x
    val cy = if (isEnd) target.endY else target.y
    val params = overlayParams(size, size).apply {
      x = (cx - size / 2f).roundToInt() - calibX
      y = (cy - size / 2f).roundToInt() - calibY
    }
    wm.addView(view, params)
    markers += view
    makeDraggable(view, view, params,
      onTap = { showTargetEditor(index) },
      onDrop = { storeMarkerPosition(view, index, isEnd) })

    if (!calibrated) {
      // Some devices offset overlay windows (status bar, cutout). Measure once and correct,
      // so the marker sits exactly where the tap will land.
      view.post {
        if (!view.isAttachedToWindow || calibrated) return@post
        val loc = IntArray(2).also(view::getLocationOnScreen)
        calibX = loc[0] - params.x
        calibY = loc[1] - params.y
        calibrated = true
        if (calibX != 0 || calibY != 0) rebuildMarkers()
      }
    }
  }

  private fun storeMarkerPosition(view: View, index: Int, isEnd: Boolean) {
    val loc = IntArray(2).also(view::getLocationOnScreen)
    val cx = loc[0] + view.width / 2f
    val cy = loc[1] + view.height / 2f
    val t = script.targets.getOrNull(index) ?: return
    val updated = when {
      // Moving the end of a recorded path straightens it.
      isEnd -> t.copy(endX = cx, endY = cy, points = emptyList())
      // Moving the start of a recorded path moves the whole path with it.
      t.points.isNotEmpty() -> {
        val dx = cx - t.x
        val dy = cy - t.y
        t.copy(x = cx, y = cy, endX = t.endX + dx, endY = t.endY + dy,
          points = t.points.map { Pt(it.x + dx, it.y + dy) })
      }
      else -> t.copy(x = cx, y = cy)
    }
    script = script.copy(targets = script.targets.toMutableList().also { it[index] = updated })
    if (!isEnd && t.points.isNotEmpty()) rebuildMarkers()
  }

  private fun setMarkersTouchable(touchable: Boolean) {
    // Markers would show up in the screenshots image steps search, so hide them while those run.
    val hide = !touchable && script.targets.any { it.type == ActionType.IMAGE }
    markers.forEach { v ->
      v.visibility = if (hide) View.INVISIBLE else View.VISIBLE
      val lp = v.layoutParams as? WLP ?: return@forEach
      lp.flags = if (touchable) lp.flags and WLP.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WLP.FLAG_NOT_TOUCHABLE
      v.alpha = if (touchable) 1f else 0.55f
      runCatching { wm.updateViewLayout(v, lp) }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun makeDraggable(handle: View, window: View, params: WLP, onTap: () -> Unit, onDrop: () -> Unit) {
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var dragging = false
    handle.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY
          startX = params.x; startY = params.y
          dragging = false
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - downX
          val dy = e.rawY - downY
          if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
          if (dragging) {
            params.x = startX + dx.roundToInt()
            params.y = startY + dy.roundToInt()
            runCatching { wm.updateViewLayout(window, params) }
          }
        }
        MotionEvent.ACTION_UP -> if (dragging) window.post(onDrop) else onTap()
      }
      true
    }
  }

  // ---------------------------------------------------------------- forms

  private fun showTargetEditor(index: Int) {
    val t = script.targets.getOrNull(index) ?: return
    if (t.type == ActionType.IMAGE) return showImageStepEditor(index)
    val body = formBody()
    val type = radioGroup(body, GESTURE_TYPES.map { it.label }, GESTURE_TYPES.indexOf(t.type))
    val press = numberField(body, "Press duration (ms)", t.pressMs)
    val delay = numberField(body, "Delay after (ms)", t.delayAfterMs)
    val randomDelay = numberField(body, "Random extra delay, up to (ms)", t.randomDelayMs)
    val randomOffset = numberField(body, "Random position offset (px)", t.randomOffsetPx.toLong())

    showForm("Target ${index + 1}", body, listOf(
      "Delete" to { removeTarget(index) },
      "Cancel" to {},
      "Save" to save@{
        val current = script.targets.getOrNull(index) ?: return@save
        val newType = GESTURE_TYPES[type.checkedIndex()]
        var pressMs = press.longOr(current.pressMs)
        // Switching type without touching the duration: use the new type's default.
        if (newType != current.type && pressMs == current.type.defaultPressMs) pressMs = newType.defaultPressMs
        var updated = current.copy(
          type = newType,
          pressMs = pressMs.coerceAtLeast(1),
          delayAfterMs = delay.longOr(current.delayAfterMs).coerceAtLeast(0),
          randomDelayMs = randomDelay.longOr(current.randomDelayMs).coerceAtLeast(0),
          randomOffsetPx = randomOffset.longOr(current.randomOffsetPx.toLong()).toInt().coerceIn(0, 500),
        )
        if (newType == ActionType.SWIPE && updated.endX == updated.x && updated.endY == updated.y) {
          updated = updated.copy(endY = (updated.y - dp(220)).coerceAtLeast(0f))
        }
        script = script.copy(targets = script.targets.toMutableList().also { it[index] = updated })
        rebuildMarkers()
      },
    ))
  }

  private fun showScriptSettings() {
    service.stopRun()
    val s = script
    val body = formBody()
    val name = textField(body, "Name", s.name)
    val mode = radioGroup(body, RunMode.entries.map { it.label }, s.mode.ordinal)
    val loops = numberField(body, "Repeat count (0 = forever)", s.loops.toLong())
    val maxDuration = numberField(body, "Stop after seconds (0 = never)", s.maxDurationSec.toLong())
    val startDelay = numberField(body, "Start delay (ms)", s.startDelayMs)
    val startAt = textField(body, "Start at time, HH:mm (empty = right away)",
      if (s.startAtMinute >= 0) formatMinute(s.startAtMinute) else "")

    showForm("Script settings", body, listOf(
      "Cancel" to {},
      "Apply" to {
        script = script.copy(
          name = name.text.toString().trim().ifEmpty { "Untitled" },
          mode = RunMode.entries[mode.checkedIndex()],
          loops = loops.longOr(s.loops.toLong()).toInt().coerceAtLeast(0),
          maxDurationSec = maxDuration.longOr(s.maxDurationSec.toLong()).toInt().coerceAtLeast(0),
          startDelayMs = startDelay.longOr(s.startDelayMs).coerceAtLeast(0),
          startAtMinute = parseMinute(startAt.text.toString()),
        )
      },
    ))
  }

  private fun formBody() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

  private fun label(parent: LinearLayout, text: String) {
    parent.addView(TextView(ctx).apply {
      this.text = text
      textSize = 13f
      setPadding(0, dp(10), 0, 0)
    })
  }

  private fun numberField(parent: LinearLayout, title: String, value: Long): EditText {
    label(parent, title)
    return EditText(ctx).apply {
      inputType = InputType.TYPE_CLASS_NUMBER
      setText(value.toString())
      setSelectAllOnFocus(true)
    }.also(parent::addView)
  }

  private fun textField(parent: LinearLayout, title: String, value: String): EditText {
    label(parent, title)
    return EditText(ctx).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      setText(value)
      setSingleLine()
    }.also(parent::addView)
  }

  private fun radioGroup(parent: LinearLayout, options: List<String>, checked: Int): RadioGroup {
    val group = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
    options.forEachIndexed { i, text ->
      val rb = RadioButton(ctx).apply {
        id = View.generateViewId()
        this.text = text
      }
      group.addView(rb)
      if (i == checked) group.check(rb.id)
    }
    parent.addView(group)
    return group
  }

  private fun RadioGroup.checkedIndex(): Int =
    indexOfChild(findViewById(checkedRadioButtonId)).coerceAtLeast(0)

  private fun EditText.longOr(fallback: Long): Long = text.toString().trim().toLongOrNull() ?: fallback

  private fun showForm(title: String, body: View, buttons: List<Pair<String, () -> Unit>>) {
    closeForm()
    val root = object : LinearLayout(ctx) {
      override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
          closeForm()
          return true
        }
        return super.dispatchKeyEvent(event)
      }
    }.apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(16), dp(20), dp(8))
      background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.WHITE)
      }
      isFocusableInTouchMode = true
    }
    root.addView(TextView(ctx).apply {
      text = title
      textSize = 18f
      setTextColor(Color.BLACK)
    })
    root.addView(ScrollView(ctx).apply { addView(body) },
      LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    val row = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END
    }
    buttons.forEach { (text, action) ->
      row.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        setOnClickListener {
          closeForm()
          action()
        }
      })
    }
    root.addView(row)

    val maxHeight = (service.resources.displayMetrics.heightPixels * 0.75f).roundToInt()
    val params = WLP(
      dp(320), WRAP_CONTENT, WLP.TYPE_ACCESSIBILITY_OVERLAY,
      WLP.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.CENTER
      dimAmount = 0.4f
      softInputMode = WLP.SOFT_INPUT_ADJUST_PAN
    }
    wm.addView(root, params)
    root.post {
      if (root.isAttachedToWindow && root.height > maxHeight) {
        params.height = maxHeight
        runCatching { wm.updateViewLayout(root, params) }
      }
    }
    root.requestFocus()
    form = root
  }

  private fun closeForm() {
    form?.let { runCatching { wm.removeView(it) } }
    form = null
  }

  private companion object {
    const val MARKER_DP = 44
    const val CAPTURE_SETTLE_MS = 300L
    val GESTURE_TYPES = listOf(ActionType.TAP, ActionType.LONG_PRESS, ActionType.SWIPE)
  }
}
