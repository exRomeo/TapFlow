package dev.tapflow.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import dev.tapflow.autoclicker.ClickerState
import dev.tapflow.autoclicker.model.ActionType
import dev.tapflow.autoclicker.model.ClickTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/** Builds the stroke for one target, applying its random position offset. */
fun ClickTarget.toStroke(random: Random = Random.Default): StrokeDescription {
  val r = randomOffsetPx
  val ox = if (r > 0) random.nextInt(-r, r + 1).toFloat() else 0f
  val oy = if (r > 0) random.nextInt(-r, r + 1).toFloat() else 0f
  fun px(v: Float) = (v + ox).coerceAtLeast(0f)
  fun py(v: Float) = (v + oy).coerceAtLeast(0f)

  val path = Path().apply {
    moveTo(px(x), py(y))
    if (type == ActionType.SWIPE) {
      // Recorded swipes keep their full path; drawn ones are a straight line.
      points.forEach { lineTo(px(it.x), py(it.y)) }
      lineTo(px(endX), py(endY))
    }
  }
  // A gesture lasting exactly the max duration is rejected, so stay 1 ms under it.
  val duration = pressMs.coerceIn(1, GestureDescription.getMaxGestureDuration() - 1)
  return StrokeDescription(path, 0, duration)
}

fun ClickTarget.toGesture(random: Random = Random.Default): GestureDescription =
  GestureDescription.Builder().addStroke(toStroke(random)).build()

/** Dispatches a gesture and suspends until the system reports it completed or was cancelled. */
suspend fun AccessibilityService.dispatchAndAwait(gesture: GestureDescription): Boolean {
  ClickerState.gestureActive.value = true
  try {
    return dispatchRaw(gesture)
  } finally {
    ClickerState.gestureActive.value = false
  }
}

private suspend fun AccessibilityService.dispatchRaw(gesture: GestureDescription): Boolean =
  suspendCancellableCoroutine { cont ->
    val callback = object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        if (cont.isActive) cont.resume(true)
      }

      override fun onCancelled(gestureDescription: GestureDescription?) {
        if (cont.isActive) cont.resume(false)
      }
    }
    if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
  }
