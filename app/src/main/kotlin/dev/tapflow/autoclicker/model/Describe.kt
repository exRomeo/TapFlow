package dev.tapflow.autoclicker.model

/** Plain-language descriptions of scripts and steps, for the details views. */

private fun pos(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"

private fun duration(ms: Long): String = when {
  ms >= 60_000 && ms % 60_000 == 0L -> "${ms / 60_000} min"
  ms >= 1000 && ms % 1000 == 0L -> "${ms / 1000} s"
  ms >= 1000 -> "%.1f s".format(ms / 1000.0)
  else -> "$ms ms"
}

/** One line per step, e.g. "Tap at (720, 60) for 40 ms, then wait 300 ms". */
fun describeStep(t: ClickTarget): String {
  val action = when (t.type) {
    ActionType.TAP -> "Tap at ${pos(t.x, t.y)}"
    ActionType.LONG_PRESS -> "Long press at ${pos(t.x, t.y)} for ${duration(t.pressMs)}"
    ActionType.SWIPE -> {
      val path = if (t.points.isNotEmpty()) "recorded path" else "straight"
      "Swipe ${pos(t.x, t.y)} → ${pos(t.endX, t.endY)} in ${duration(t.pressMs)} ($path)"
    }
    ActionType.IMAGE -> {
      val n = t.imageFiles.size
      val notFound = if (t.stopIfNotFound) "stop script" else "skip"
      "Find ${if (n == 1) "image" else "any of $n images"} (${(t.matchThreshold * 100).toInt()}% match, " +
        "up to ${duration(t.waitTimeoutMs)}, else $notFound) and tap it"
    }
  }
  val extras = buildList {
    if (t.randomDelayMs > 0) add("+0–${duration(t.randomDelayMs)} random wait")
    if (t.randomOffsetPx > 0) add("±${t.randomOffsetPx} px")
  }
  val wait = "then wait ${duration(t.delayAfterMs)}"
  return listOf(action, wait).plus(extras).joinToString(", ")
}

/** "1. Collect reward" for a named step, "1. Tap" otherwise. */
fun stepTitle(index: Int, t: ClickTarget): String =
  "${index + 1}. ${t.name?.takeIf { it.isNotBlank() } ?: t.type.label}"

/** Summary lines for a whole script: mode, repeats, limits and schedule. */
fun describeScript(s: Script): List<String> = buildList {
  add("Mode: ${s.panelMode.title}")
  add(
    "Steps: ${s.targets.size}" +
      if (s.mode == RunMode.SYNC) " (all at once)" else " (in order)",
  )
  add(if (s.loops == 0) "Repeats: forever" else "Repeats: ${s.loops}×")
  if (s.maxDurationSec > 0) add("Stops after: ${duration(s.maxDurationSec * 1000L)}")
  if (s.startDelayMs > 0) add("Start delay: ${duration(s.startDelayMs)}")
  if (s.startAtMinute >= 0) add("Starts at: ${formatMinute(s.startAtMinute)}")
}
