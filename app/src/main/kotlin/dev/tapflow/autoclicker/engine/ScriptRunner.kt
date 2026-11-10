package dev.tapflow.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.media.AudioManager
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
  /** Called once when a run ends; [reason] says why it was stopped early, if it was. */
  private val onStopped: (reason: String?) -> Unit = {},
) {
  private var job: Job? = null
  private var stopReason: String? = null
  private val random = Random.Default
  private val audio = service.getSystemService(AudioManager::class.java)

  val isRunning: Boolean get() = job?.isActive == true

  /** Where a paused run left off: the next step to do, and how much run time was used. */
  private data class Progress(val script: Script, val loop: Int, val index: Int, val elapsedMs: Long)

  /** Saved when a run is paused part-way; null when there is nothing to resume. */
  private var paused: Progress? = null

  // Live position of the current run, kept so a pause can save it.
  private var current: Script? = null
  private var nextLoop = 0
  private var nextIndex = 0
  private var elapsedBefore = 0L
  private var executingSince = 0L
  private var executing = false

  /**
   * Starts [script], or resumes it from where it was paused if it hasn't changed since.
   * Editing the script, or [clearPause], makes the next start begin from step 1.
   */
  fun start(script: Script) {
    if (script.targets.isEmpty()) return
    val resume = paused?.takeIf { it.script == script }
    stop()
    clearPause()
    stopReason = null
    // Set before launching so the overlay starts making markers untouchable right away.
    ClickerState.running.value = true
    job = scope.launch {
      try {
        run(script, resume)
      } finally {
        // A cancelled previous run must not clear the flag for the run that replaced it.
        val self = coroutineContext[Job]
        if (job == null || job === self) {
          job = null
          executing = false
          ClickerState.currentStep.value = ClickerState.NO_STEP
          ClickerState.running.value = false
          ClickerState.waitingForSchedule.value = false
          val reason = stopReason
          stopReason = null
          onStopped(reason)
        }
      }
    }
  }

  /**
   * Pauses the current run, remembering the next step so ▶ can resume it. A [reason] is
   * shown to the user; null means they pressed pause.
   */
  fun stop(reason: String? = null) {
    if (job?.isActive == true) {
      if (reason != null) stopReason = reason
      val script = current
      if (executing && script != null) {
        paused = Progress(script, nextLoop, nextIndex, elapsedBefore + SystemClock.elapsedRealtime() - executingSince)
        ClickerState.paused.value = true
      }
    }
    job?.cancel()
    job = null
  }

  /** Forgets a paused position, so the next start begins from step 1. */
  fun clearPause() {
    paused = null
    ClickerState.paused.value = false
  }

  /** A phone or VoIP call is ringing or in progress. Needs no permission. */
  private fun inCall(): Boolean = audio.mode in setOf(
    AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
  )

  private suspend fun CoroutineScope.run(script: Script, resume: Progress?) {
    current = script
    if (resume == null) {
      val untilScheduled = script.millisUntilScheduledStart()
      if (untilScheduled > 0) Log.d(TAG, "scheduled start in ${untilScheduled / 1000}s")
      ClickerState.waitingForSchedule.value = untilScheduled > 0
      // Window flag changes apply asynchronously; give the markers time to become
      // untouchable, otherwise the first gesture lands on our own marker.
      delay((untilScheduled + script.startDelayMs).coerceAtLeast(MIN_START_DELAY_MS))
      ClickerState.waitingForSchedule.value = false
    } else {
      Log.d(TAG, "resuming at loop ${resume.loop}, step ${resume.index + 1}")
      delay(MIN_START_DELAY_MS)
    }

    nextLoop = resume?.loop ?: 0
    nextIndex = resume?.index ?: 0
    elapsedBefore = resume?.elapsedMs ?: 0
    executingSince = SystemClock.elapsedRealtime()
    executing = true

    val deadline =
      if (script.maxDurationSec > 0) executingSince + script.maxDurationSec * 1000L - elapsedBefore
      else Long.MAX_VALUE
    fun timeLeft() = SystemClock.elapsedRealtime() < deadline
    // Never wait past the stop-after deadline.
    fun capped(wait: Long) = minOf(wait, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))

    var loop = nextLoop
    var firstIndex = nextIndex
    while (isActive && timeLeft() && (script.loops == 0 || loop < script.loops)) {
      when (script.mode) {
        RunMode.SEQUENCE -> {
          for (i in firstIndex until script.targets.size) {
            val target = script.targets[i]
            if (!isActive || !timeLeft()) break
            // A new round starts with every marker white again.
            if (i == 0) ClickerState.currentStep.value = ClickerState.NO_STEP
            if (inCall()) {
              stop("a call started")
              return
            }
            val action = if (target.type == ActionType.IMAGE) {
              val match = waitForImage(target, deadline)
              if (match == null) {
                if (target.stopIfNotFound) {
                  Log.d(TAG, "image not found, stopping script")
                  stopReason = "the image wasn't found"
                  executing = false
                  return
                }
                Log.d(TAG, "image not found, skipping step")
                advance(script, loop, i)
                continue
              }
              target.copy(type = ActionType.TAP, x = match.centerX, y = match.centerY)
            } else {
              target
            }
            ClickerState.currentStep.value = i
            val ok = service.dispatchAndAwait(action.toGesture(random))
            advance(script, loop, i)
            val wait = jitteredDelay(target)
            Log.d(TAG, "loop=$loop ${target.type} at (${action.x.toInt()}, ${action.y.toInt()}) completed=$ok, waiting ${wait}ms")
            delay(capped(wait))
          }
          firstIndex = 0
        }
        RunMode.SYNC -> {
          // Image steps need waiting and searching, so they only run in order mode.
          val targets = script.targets.filter { it.type != ActionType.IMAGE }
            .take(GestureDescription.getMaxStrokeCount())
          if (targets.isEmpty()) return
          if (inCall()) {
            stop("a call started")
            return
          }
          ClickerState.currentStep.value = ClickerState.ALL_STEPS
          val builder = GestureDescription.Builder()
          targets.forEach { builder.addStroke(it.toStroke(random)) }
          val ok = service.dispatchAndAwait(builder.build())
          nextLoop = loop + 1
          nextIndex = 0
          val wait = targets.maxOf { jitteredDelay(it) }
          Log.d(TAG, "loop=$loop sync strokes=${targets.size} completed=$ok, waiting ${wait}ms")
          delay(capped(wait))
        }
      }
      loop++
    }
    // Finished normally: nothing to resume.
    executing = false
    Log.d(TAG, "finished after $loop loops")
  }

  /** Records that step [index] of [loop] is done, so a pause resumes after it. */
  private fun advance(script: Script, loop: Int, index: Int) {
    if (index + 1 < script.targets.size) {
      nextLoop = loop
      nextIndex = index + 1
    } else {
      nextLoop = loop + 1
      nextIndex = 0
    }
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
