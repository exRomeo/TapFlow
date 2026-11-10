package dev.tapflow.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import android.util.Log
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.Prefs
import dev.tapflow.autoclicker.data.TemplateStore
import dev.tapflow.autoclicker.model.ActionType
import dev.tapflow.autoclicker.model.ClickTarget
import dev.tapflow.autoclicker.model.RunMode
import dev.tapflow.autoclicker.model.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Plays a [Script] by dispatching gestures through the accessibility service. */
class ScriptRunner(
  private val service: AccessibilityService,
  private val scope: CoroutineScope,
  private val templates: TemplateStore,
) {
  private var job: Job? = null
  private val random = Random.Default

  val isRunning: Boolean get() = job?.isActive == true

  fun start(script: Script) {
    if (script.targets.isEmpty()) return
    stop()
    // Set before launching so the overlay starts making markers untouchable right away.
    ClickerState.running.value = true
    job = scope.launch {
      try {
        run(script)
      } finally {
        // A cancelled previous run must not clear the flag for the run that replaced it.
        val self = coroutineContext[Job]
        if (job == null || job === self) {
          job = null
          ClickerState.running.value = false
        }
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  private suspend fun CoroutineScope.run(script: Script) {
    val untilScheduled = script.millisUntilScheduledStart()
    if (untilScheduled > 0) Log.d(TAG, "scheduled start in ${untilScheduled / 1000}s")
    // Window flag changes apply asynchronously; give the markers time to become
    // untouchable, otherwise the first gesture lands on our own marker.
    delay((untilScheduled + script.startDelayMs).coerceAtLeast(MIN_START_DELAY_MS))
    val deadline =
      if (script.maxDurationSec > 0) SystemClock.elapsedRealtime() + script.maxDurationSec * 1000L
      else Long.MAX_VALUE
    fun timeLeft() = SystemClock.elapsedRealtime() < deadline
    // Never wait past the stop-after deadline.
    fun capped(wait: Long) = minOf(wait, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))

    var loop = 0
    while (isActive && timeLeft() && (script.loops == 0 || loop < script.loops)) {
      when (script.mode) {
        RunMode.SEQUENCE ->
          for (target in script.targets) {
            if (!isActive || !timeLeft()) break
            val action = if (target.type == ActionType.IMAGE) {
              val match = waitForImage(target, deadline)
              if (match == null) {
                if (target.stopIfNotFound) {
                  Log.d(TAG, "image not found, stopping script")
                  return
                }
                Log.d(TAG, "image not found, skipping step")
                continue
              }
              target.copy(type = ActionType.TAP, x = match.centerX, y = match.centerY)
            } else {
              target
            }
            val ok = service.dispatchAndAwait(action.toGesture(random))
            val wait = jitteredDelay(target)
            Log.d(TAG, "loop=$loop ${target.type} at (${action.x.toInt()}, ${action.y.toInt()}) completed=$ok, waiting ${wait}ms")
            delay(capped(wait))
          }
        RunMode.SYNC -> {
          // Image steps need waiting and searching, so they only run in order mode.
          val targets = script.targets.filter { it.type != ActionType.IMAGE }
            .take(GestureDescription.getMaxStrokeCount())
          if (targets.isEmpty()) return
          val builder = GestureDescription.Builder()
          targets.forEach { builder.addStroke(it.toStroke(random)) }
          val ok = service.dispatchAndAwait(builder.build())
          val wait = targets.maxOf { jitteredDelay(it) }
          Log.d(TAG, "loop=$loop sync strokes=${targets.size} completed=$ok, waiting ${wait}ms")
          delay(capped(wait))
        }
      }
      loop++
    }
    Log.d(TAG, "finished after $loop loops")
  }

  /**
   * Polls screenshots until one of the step's images is found, its timeout passes, or the
   * script deadline hits. With several images, the best-scoring one above the threshold wins.
   */
  private suspend fun waitForImage(target: ClickTarget, deadline: Long): MatchResult? {
    if (!Prefs.imageStepsAllowed(service)) {
      Log.w(TAG, "image steps need the updated consent, skipping")
      return null
    }
    val images = withContext(Dispatchers.IO) { target.imageFiles.mapNotNull(templates::load) }
    if (images.isEmpty()) {
      Log.w(TAG, "image step has no usable images")
      return null
    }
    val giveUpAt = minOf(SystemClock.elapsedRealtime() + target.waitTimeoutMs, deadline)
    while (true) {
      val shotAt = SystemClock.elapsedRealtime()
      val screen = service.captureScreen()
      if (screen != null) {
        val best = withContext(Dispatchers.Default) {
          images.mapNotNull { TemplateMatcher.find(screen, it) }.maxByOrNull { it.score }
        }
        screen.recycle()
        Log.d(TAG, "image score=${"%.3f".format(best?.score ?: 0.0)} (need ${target.matchThreshold}, ${images.size} image(s))")
        if (best != null && best.score >= target.matchThreshold) return best
      }
      val now = SystemClock.elapsedRealtime()
      if (now >= giveUpAt) return null
      delay((SCREENSHOT_INTERVAL_MS - (now - shotAt)).coerceAtLeast(50))
    }
  }

  private fun jitteredDelay(t: ClickTarget): Long =
    t.delayAfterMs.coerceAtLeast(0) + if (t.randomDelayMs > 0) random.nextLong(t.randomDelayMs + 1) else 0

  private companion object {
    const val TAG = "TapFlow"
    const val MIN_START_DELAY_MS = 200L
    /** The system rejects screenshots requested less than ~333 ms apart. */
    const val SCREENSHOT_INTERVAL_MS = 400L
  }
}
