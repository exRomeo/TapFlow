package dev.tapflow.autoclicker.overlay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.DisplayMetrics
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
import dev.tapflow.autoclicker.model.NEW_TARGET_DELAY_MS
import dev.tapflow.autoclicker.model.PanelMode
import dev.tapflow.autoclicker.model.describeStep
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

  private var panel: LinearLayout? = null
  private var panelParams: WLP? = null
  private val panelButtons = mutableMapOf<PanelButton, TextView>()
  /** Where the panel was last placed, so rebuilding it (new mode or look) keeps its spot. */
  private var panelX: Int? = null
  private var panelY: Int? = null
  private val markers = mutableListOf<View>()
  /** The user hid the step markers with 👁; the script still runs normally. */
  private var markersHidden = false
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
    scope.launch { ClickerState.paused.collect { updatePanelButtons() } }
    scope.launch { ClickerState.currentStep.collect { highlightStep() } }
    scope.launch { ClickerState.gestureActive.collect { highlightStep() } }
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

  /** The buttons a panel can have; each mode shows a subset. */
  private enum class PanelButton { HANDLE, PLAY, RESTART, ADD_TAP, ADD_SWIPE, RECORD, IMAGE, REMOVE, MARKERS, SETTINGS, SAVE, CLOSE }

  private fun buttonsFor(mode: PanelMode): List<PanelButton> = when (mode) {
    PanelMode.SINGLE -> listOf(PanelButton.HANDLE, PanelButton.PLAY, PanelButton.RESTART, PanelButton.MARKERS, PanelButton.SETTINGS, PanelButton.SAVE, PanelButton.CLOSE)
    PanelMode.MULTI -> listOf(PanelButton.HANDLE, PanelButton.PLAY, PanelButton.RESTART, PanelButton.ADD_TAP, PanelButton.ADD_SWIPE, PanelButton.REMOVE, PanelButton.MARKERS, PanelButton.SETTINGS, PanelButton.SAVE, PanelButton.CLOSE)
    PanelMode.SYNC -> listOf(PanelButton.HANDLE, PanelButton.PLAY, PanelButton.RESTART, PanelButton.ADD_TAP, PanelButton.REMOVE, PanelButton.MARKERS, PanelButton.SETTINGS, PanelButton.SAVE, PanelButton.CLOSE)
    PanelMode.RECORD -> listOf(PanelButton.HANDLE, PanelButton.PLAY, PanelButton.RESTART, PanelButton.RECORD, PanelButton.REMOVE, PanelButton.MARKERS, PanelButton.SETTINGS, PanelButton.SAVE, PanelButton.CLOSE)
    PanelMode.IMAGE -> listOf(PanelButton.HANDLE, PanelButton.PLAY, PanelButton.RESTART, PanelButton.IMAGE, PanelButton.ADD_TAP, PanelButton.REMOVE, PanelButton.MARKERS, PanelButton.SETTINGS, PanelButton.SAVE, PanelButton.CLOSE)
    PanelMode.ADVANCED -> PanelButton.entries
  }

  /** Shows the panel for the current script's mode, rebuilding it if it is already open. */
  fun showPanel() {
    // Modes with one fixed run style keep the script in that style.
    script = when (script.panelMode) {
      PanelMode.SYNC -> script.copy(mode = RunMode.SYNC)
      PanelMode.ADVANCED -> script
      else -> script.copy(mode = RunMode.SEQUENCE)
    }
    if (script.panelMode == PanelMode.SINGLE && script.targets.isEmpty()) {
      val dm = service.resources.displayMetrics
      script = script.copy(targets = listOf(ClickTarget(ActionType.TAP, dm.widthPixels / 2f, dm.heightPixels / 2f, delayAfterMs = NEW_TARGET_DELAY_MS)))
    }
    removePanel()
    addPanel()
    rebuildMarkers()
  }

  /** Whether a screen point falls on the floating panel. */
  fun isOverPanel(x: Float, y: Float): Boolean {
    val view = panel ?: return false
    val loc = IntArray(2).also(view::getLocationOnScreen)
    return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
  }

  /** Re-applies size, opacity and orientation settings to an open panel and its markers. */
  fun refreshAppearance() {
    if (panel == null) return
    removePanel()
    addPanel()
    rebuildMarkers()
  }

  fun hideAll() {
    service.stopRun()
    if (recorder.isRecording) recorder.stop()
    closeForm()
    removeMarkers()
    removePanel()
  }

  private fun removePanel() {
    panelParams?.let { panelX = it.x; panelY = it.y }
    panel?.let { runCatching { wm.removeView(it) } }
    panel = null
    panelParams = null
    panelButtons.clear()
  }

  private fun panelScale() = Prefs.panelSize(service).coerceIn(50, 200) / 100f

  private fun addPanel() {
    val scale = panelScale()
    val root = LinearLayout(ctx).apply {
      orientation = panelOrientation()
      setPadding(dp(4), dp(6), dp(4), dp(6))
      background = GradientDrawable().apply {
        cornerRadius = 26 * density * scale
        setColor(0xE6202124.toInt())
        // Light outline keeps the panel visible on dark screens.
        setStroke(dp(1), 0x66FFFFFF)
      }
      alpha = Prefs.panelOpacity(service).coerceIn(20, 100) / 100f
    }

    fun button(glyph: String, description: String, color: Int = Color.WHITE, onClick: () -> Unit) = TextView(ctx).apply {
      text = glyph
      textSize = 20f * scale
      setTextColor(color)
      gravity = Gravity.CENTER
      contentDescription = description
      val size = (44 * density * scale).roundToInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
      setOnClickListener { onClick() }
    }.also(root::addView)

    for (b in buttonsFor(script.panelMode)) {
      panelButtons[b] = when (b) {
        PanelButton.HANDLE -> button("≡", "Move panel") {}
        PanelButton.PLAY -> button("▶", "Start, pause or resume") { service.toggleRun() }
        PanelButton.RESTART -> button("⟲", "Start over from step 1") { service.restartRun() }
        PanelButton.ADD_TAP -> button("+", "Add tap target") { addTarget(ActionType.TAP) }
        PanelButton.ADD_SWIPE -> button("↗", "Add swipe target") { addTarget(ActionType.SWIPE) }
        PanelButton.RECORD -> button("●", "Record gestures", color = 0xFFFF5252.toInt()) { startRecording() }
        PanelButton.IMAGE -> button("🖼", "Add image step") { captureImage(appendTo = null) }
        PanelButton.REMOVE -> button("−", "Remove last target") { removeTarget(script.targets.lastIndex) }
        PanelButton.MARKERS -> button(if (markersHidden) "🙈" else "👁", "Hide or show step markers") { toggleMarkers() }
        PanelButton.SETTINGS -> button("⚙", "Script settings and steps") { showScriptSettings() }
        PanelButton.SAVE -> button("💾", "Save script") { service.saveScript() }
        PanelButton.CLOSE -> button("✕", "Close panel") { hideAll() }
      }
    }

    val params = overlayParams(WRAP_CONTENT, WRAP_CONTENT).apply {
      x = panelX ?: dp(8)
      y = panelY ?: dp(160)
    }
    wm.addView(root, params)
    panelButtons[PanelButton.HANDLE]?.let { makeDraggable(it, root, params, Bounds.WHOLE_WINDOW, onTap = {}, onDrop = {}) }
    panel = root
    panelParams = params
    onRunningChanged(ClickerState.running.value)
    // Keep it on screen if the new size or orientation pushed it past an edge.
    root.post { if (panel === root) keepPanelOnScreen() }
  }

  private fun onRunningChanged(running: Boolean) {
    if (running) closeForm()
    updatePanelButtons()
    setMarkersTouchable(!running)
    setKeepScreenOn(running && Prefs.keepScreenOn(service))
  }

  /**
   * While running, the panel can shrink to just the pause button; it grows back on pause.
   * The start-over button only shows while a run is paused.
   */
  private fun updatePanelButtons() {
    val running = ClickerState.running.value
    val paused = ClickerState.paused.value
    val compact = running && Prefs.compactWhileRunning(service)
    panelButtons[PanelButton.PLAY]?.apply {
      text = if (running) "⏸" else "▶"
      contentDescription = when {
        running -> "Pause"
        paused -> "Resume"
        else -> "Start"
      }
    }
    for ((b, view) in panelButtons) {
      view.visibility = when {
        b == PanelButton.PLAY -> View.VISIBLE
        compact -> View.GONE
        b == PanelButton.RESTART -> if (paused && !running) View.VISIBLE else View.GONE
        else -> View.VISIBLE
      }
    }
  }

  /** The visible panel window holds the screen on while a script runs or waits to start. */
  private fun setKeepScreenOn(on: Boolean) {
    val view = panel ?: return
    val lp = panelParams ?: return
    lp.flags = if (on) lp.flags or WLP.FLAG_KEEP_SCREEN_ON else lp.flags and WLP.FLAG_KEEP_SCREEN_ON.inv()
    runCatching { wm.updateViewLayout(view, lp) }
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
      ClickTarget(type, cx, cy + dp(110), endX = cx, endY = cy - dp(110), delayAfterMs = NEW_TARGET_DELAY_MS)
    } else {
      ClickTarget(type, cx, cy, delayAfterMs = NEW_TARGET_DELAY_MS)
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

  private fun toggleMarkers() {
    markersHidden = !markersHidden
    panelButtons[PanelButton.MARKERS]?.text = if (markersHidden) "🙈" else "👁"
    rebuildMarkers()
    service.toast(if (markersHidden) "Markers hidden. The script still runs." else "Markers shown")
  }

  private fun removeMarkers() {
    markers.forEach { runCatching { wm.removeView(it) } }
    markers.clear()
  }

  private fun rebuildMarkers() {
    removeMarkers()
    if (panel == null || markersHidden) return
    script.targets.forEachIndexed { i, t ->
      // Add the end point first so the start marker is drawn on top of it.
      if (t.type == ActionType.SWIPE) addMarker(i, t, isEnd = true)
      addMarker(i, t, isEnd = false)
    }
    setMarkersTouchable(!ClickerState.running.value)
    highlightStep()
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun addMarker(index: Int, target: ClickTarget, isEnd: Boolean) {
    val size = (MARKER_DP * density * Prefs.markerSize(service).coerceIn(50, 200) / 100f).roundToInt()
    val label = if (isEnd) "${index + 1}'" else "${index + 1}"
    val view = MarkerView(ctx, label, target.type, isEnd).apply { tag = index }
    val cx = if (isEnd) target.endX else target.x
    val cy = if (isEnd) target.endY else target.y
    val params = overlayParams(size, size).apply {
      x = (cx - size / 2f).roundToInt() - calibX
      y = (cy - size / 2f).roundToInt() - calibY
    }
    wm.addView(view, params)
    markers += view
    makeDraggable(view, view, params, Bounds.CENTER_ONLY,
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

  /**
   * Traffic lights while running: green only while a step's gesture is being performed,
   * red once it's done this round, yellow for the step coming next, white for the rest.
   * Coloured markers stay fully visible; white ones fade.
   */
  private fun highlightStep() {
    val running = ClickerState.running.value
    val step = if (running) ClickerState.currentStep.value else ClickerState.NO_STEP
    val pressing = ClickerState.gestureActive.value
    val count = script.targets.size
    val base = Prefs.markerOpacity(service).coerceIn(20, 100) / 100f
    markers.forEach { v ->
      val marker = v as? MarkerView ?: return@forEach
      val index = marker.tag as? Int ?: return@forEach
      marker.state = when {
        !running -> StepState.IDLE
        step == ClickerState.ALL_STEPS -> if (pressing) StepState.CURRENT else StepState.DONE
        index == step -> if (pressing) StepState.CURRENT else StepState.DONE
        index == (step + 1) % count -> StepState.NEXT
        index < step -> StepState.DONE
        else -> StepState.IDLE
      }
      if (running) marker.alpha = if (marker.state == StepState.IDLE) base * 0.55f else base
    }
  }

  private fun setMarkersTouchable(touchable: Boolean) {
    // Markers would show up in the screenshots image steps search, so hide them while those run.
    val hide = !touchable && script.targets.any { it.type == ActionType.IMAGE }
    markers.forEach { v ->
      v.visibility = if (hide) View.INVISIBLE else View.VISIBLE
      val lp = v.layoutParams as? WLP ?: return@forEach
      lp.flags = if (touchable) lp.flags and WLP.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WLP.FLAG_NOT_TOUCHABLE
      val base = Prefs.markerOpacity(service).coerceIn(20, 100) / 100f
      v.alpha = if (touchable) base else base * 0.55f
      runCatching { wm.updateViewLayout(v, lp) }
    }
  }

  /** How much of a dragged window must stay on screen. */
  private enum class Bounds {
    /** The panel: all of it, so every button stays reachable. */
    WHOLE_WINDOW,
    /** A marker: its centre, which is where the tap lands. */
    CENTER_ONLY,
  }

  /** Full display size in pixels, including system bars: the space gestures use. */
  private fun screenSize(): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val b = wm.maximumWindowMetrics.bounds
      return b.width() to b.height()
    }
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(dm)
    return dm.widthPixels to dm.heightPixels
  }

  /**
   * Moves [params] to the requested position, kept inside the screen. Window positions are
   * offset by the measured calibration, so the clamp works on real screen coordinates.
   */
  private fun placeWithin(window: View, params: WLP, x: Int, y: Int, bounds: Bounds) {
    val (w, h) = screenSize()
    val vw = window.width.takeIf { it > 0 } ?: params.width.coerceAtLeast(0)
    val vh = window.height.takeIf { it > 0 } ?: params.height.coerceAtLeast(0)
    val (minX, maxX, minY, maxY) = when (bounds) {
      Bounds.WHOLE_WINDOW -> listOf(0, (w - vw).coerceAtLeast(0), 0, (h - vh).coerceAtLeast(0))
      Bounds.CENTER_ONLY -> listOf(-vw / 2, w - 1 - vw / 2, -vh / 2, h - 1 - vh / 2)
    }
    params.x = (x + calibX).coerceIn(minX, maxX) - calibX
    params.y = (y + calibY).coerceIn(minY, maxY) - calibY
  }

  /**
   * Vertical unless that would be taller than the screen (landscape), so every button
   * always fits on screen.
   */
  private fun panelOrientation(): Int = when (Prefs.panelOrientation(service)) {
    "vertical" -> LinearLayout.VERTICAL
    "horizontal" -> LinearLayout.HORIZONTAL
    else -> {
      val (_, h) = screenSize()
      val buttons = buttonsFor(script.panelMode).size * 44 * density * panelScale() + dp(12)
      if (buttons > h * 0.9f) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }
  }

  /** Re-lays the panel for the current screen and pulls it back on screen, e.g. after rotating. */
  fun keepPanelOnScreen() {
    val view = panel ?: return
    val lp = panelParams ?: return
    view.orientation = panelOrientation()
    runCatching { wm.updateViewLayout(view, lp) }
    // Clamp once the new size is measured.
    view.post {
      if (panel !== view) return@post
      placeWithin(view, lp, lp.x, lp.y, Bounds.WHOLE_WINDOW)
      runCatching { wm.updateViewLayout(view, lp) }
    }
  }

  /**
   * Near the screen edges Android treats a sideways drag as the "back" gesture and takes the
   * touch away mid-drag. Excluding the drag handle keeps edge drags with TapFlow.
   */
  private fun excludeFromSystemGestures(view: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    view.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
      v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun makeDraggable(
    handle: View, window: View, params: WLP, bounds: Bounds, onTap: () -> Unit, onDrop: () -> Unit,
  ) {
    excludeFromSystemGestures(handle)
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
            placeWithin(window, params, startX + dx.roundToInt(), startY + dy.roundToInt(), bounds)
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

  /** Script settings in two tabs: Controls (how it runs) and Steps (rename or remove steps). */
  private fun showScriptSettings(startOnSteps: Boolean = false) {
    service.stopRun()
    val s = script

    // ---- Controls tab
    val controls = formBody()
    val name = textField(controls, "Script name", s.name)
    // Other modes have a fixed run style (Sync is all at once, the rest in order).
    val mode = if (s.panelMode == PanelMode.ADVANCED) {
      label(controls, "Run steps")
      radioGroup(controls, RunMode.entries.map { it.label }, s.mode.ordinal)
    } else {
      null
    }
    val loops = numberField(controls, "Repeat count (0 = forever)", s.loops.toLong())
    val maxDuration = numberField(controls, "Stop after seconds (0 = never)", s.maxDurationSec.toLong())
    val startDelay = numberField(controls, "Start delay (ms)", s.startDelayMs)
    val startAt = textField(controls, "Start at time, HH:mm (empty = right away)",
      if (s.startAtMinute >= 0) formatMinute(s.startAtMinute) else "")

    // ---- Steps tab: rename with the text box, remove with ✕ (both take effect on Apply)
    val steps = formBody()
    val removed = mutableSetOf<Int>()
    val nameFields = mutableMapOf<Int, EditText>()
    val numberViews = mutableMapOf<Int, TextView>()
    // Number the steps that are left, so removing one renumbers the rest like Apply will.
    fun renumber() {
      var n = 1
      s.targets.indices.filter { it !in removed }.forEach { numberViews[it]?.text = "${n++}." }
    }
    val stepsTabTitle = { "Steps (${s.targets.size - removed.size})" }
    var stepsTab: TextView? = null
    if (s.targets.isEmpty()) label(steps, "No steps yet. Add some from the panel.")
    s.targets.forEachIndexed { i, t ->
      val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
      }
      val top = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      top.addView(TextView(ctx).apply {
        text = "${i + 1}."
        textSize = 15f
        setTextColor(Color.BLACK)
        setPadding(0, 0, dp(6), 0)
      }.also { numberViews[i] = it })
      val field = EditText(ctx).apply {
        setText(t.name.orEmpty())
        hint = t.type.label
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine()
      }
      nameFields[i] = field
      top.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
      top.addView(TextView(ctx).apply {
        text = "✕"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFC62828.toInt())
        contentDescription = "Remove step ${i + 1}"
        setOnClickListener {
          removed += i
          row.visibility = View.GONE
          renumber()
          stepsTab?.text = stepsTabTitle()
        }
      }, LinearLayout.LayoutParams(dp(40), dp(40)))
      row.addView(top)
      row.addView(TextView(ctx).apply {
        text = describeStep(t)
        textSize = 12f
        setTextColor(0xFF5F6368.toInt())
      })
      steps.addView(row)
    }
    if (s.targets.isNotEmpty()) label(steps, "Removed steps are deleted when you tap Apply.")

    // ---- tab header
    val body = formBody().apply {
      addView(controls)
      addView(steps)
    }
    val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    fun tab(text: String) = TextView(ctx).apply {
      this.text = text
      textSize = 15f
      gravity = Gravity.CENTER
      setPadding(0, dp(10), 0, dp(10))
    }.also { tabs.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    val controlsTab = tab("Controls")
    stepsTab = tab(stepsTabTitle())
    fun select(onSteps: Boolean) {
      controls.visibility = if (onSteps) View.GONE else View.VISIBLE
      steps.visibility = if (onSteps) View.VISIBLE else View.GONE
      listOf(controlsTab to !onSteps, stepsTab!! to onSteps).forEach { (v, selected) ->
        v.setTextColor(if (selected) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt())
        v.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        v.background = if (selected) {
          GradientDrawable().apply { setColor(0x221A73E8) ; cornerRadius = dp(10).toFloat() }
        } else {
          null
        }
      }
    }
    controlsTab.setOnClickListener { select(false) }
    stepsTab!!.setOnClickListener { select(true) }
    select(startOnSteps)

    showForm("Script: ${s.name}", body, listOf(
      "Cancel" to {},
      "Apply" to {
        val kept = s.targets.mapIndexedNotNull { i, t ->
          if (i in removed) null
          else t.copy(name = nameFields[i]?.text?.toString()?.trim()?.ifEmpty { null })
        }
        script = script.copy(
          name = name.text.toString().trim().ifEmpty { "Untitled" },
          mode = mode?.let { RunMode.entries[it.checkedIndex()] } ?: script.mode,
          loops = loops.longOr(s.loops.toLong()).toInt().coerceAtLeast(0),
          maxDurationSec = maxDuration.longOr(s.maxDurationSec.toLong()).toInt().coerceAtLeast(0),
          startDelayMs = startDelay.longOr(s.startDelayMs).coerceAtLeast(0),
          startAtMinute = parseMinute(startAt.text.toString()),
          targets = kept,
        )
        rebuildMarkers()
      },
    ), header = tabs, fullHeight = true)
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

  private fun showForm(
    title: String,
    body: View,
    buttons: List<Pair<String, () -> Unit>>,
    header: View? = null,
    fullHeight: Boolean = false,
  ) {
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
    header?.let(root::addView)
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
    if (fullHeight) {
      // Tabs of different lengths shouldn't make the window jump in size.
      params.height = maxHeight
      runCatching { wm.updateViewLayout(root, params) }
    }
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
