package dev.tapflow.autoclicker.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

@Serializable
enum class ActionType(val label: String, val defaultPressMs: Long) {
  TAP("Tap", 40),
  LONG_PRESS("Long press", 800),
  SWIPE("Swipe", 300),
  /** Waits for one of its captured images to appear on screen, then taps it. */
  IMAGE("Tap image", 40),
}

@Serializable
enum class RunMode(val label: String) {
  /** Targets fire one after another, each followed by its own delay. */
  SEQUENCE("In order"),
  /** All targets fire at the same moment as one multi-touch gesture. */
  SYNC("All at once"),
}

/**
 * What the floating panel is set up for. Each mode shows only the buttons it needs,
 * so the panel isn't crowded with tools the user isn't using.
 */
@Serializable
enum class PanelMode(val title: String, val description: String, val icon: String) {
  SINGLE("Single target", "One spot, tapped again and again", "👆"),
  MULTI("Multi targets", "Several taps and swipes, one after another", "🔢"),
  SYNC("Sync", "Several spots tapped at the same moment", "🖐"),
  RECORD("Record", "Do it once yourself, then replay it", "⏺"),
  IMAGE("Find image", "Wait for something to appear, then tap it", "🖼"),
  ADVANCED("Everything", "All tools in one panel", "🧰"),
}

@Serializable
data class Pt(val x: Float, val y: Float)

/** Default wait after each step, in milliseconds. */
const val NEW_TARGET_DELAY_MS = 100L

/** One point (or swipe) on screen. Coordinates are absolute screen pixels. */
@Serializable
data class ClickTarget(
  val type: ActionType = ActionType.TAP,
  val x: Float,
  val y: Float,
  val endX: Float = x,
  val endY: Float = y,
  val pressMs: Long = type.defaultPressMs,
  val delayAfterMs: Long = NEW_TARGET_DELAY_MS,
  /** Extra random 0..N ms added to the delay, for human-like pacing. */
  val randomDelayMs: Long = 0,
  /** Random +/- N px applied to the touch position. */
  val randomOffsetPx: Int = 0,
  /** Intermediate points of a recorded swipe, between start and end. Empty = straight line. */
  val points: List<Pt> = emptyList(),
  /** IMAGE only: file names of the images to look for (see TemplateStore). Any one of them counts. */
  val imageFiles: List<String> = emptyList(),
  /** IMAGE only: minimum similarity, 0..1, to count as found. */
  val matchThreshold: Float = 0.85f,
  /** IMAGE only: how long to keep looking before giving up. */
  val waitTimeoutMs: Long = 10_000,
  /** IMAGE only: stop the whole script if the image never shows up (otherwise skip this step). */
  val stopIfNotFound: Boolean = false,
  /** Optional name the user gave this step, e.g. "Collect reward". */
  val name: String? = null,
)

@Serializable
data class Script(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "Untitled",
  val mode: RunMode = RunMode.SEQUENCE,
  val targets: List<ClickTarget> = emptyList(),
  /** 0 = loop forever. */
  val loops: Int = 0,
  /** 0 = no time limit. */
  val maxDurationSec: Int = 0,
  val startDelayMs: Long = 0,
  /** Minute of the day (0..1439) to start at, or -1 to start right away. */
  val startAtMinute: Int = -1,
  /** The panel mode this script was made in; it reopens in the same mode. */
  val panelMode: PanelMode = PanelMode.ADVANCED,
  val updatedAt: Long = System.currentTimeMillis(),
) {
  /** Milliseconds until the next occurrence of [startAtMinute], or 0 if not scheduled. */
  fun millisUntilScheduledStart(now: LocalDateTime = LocalDateTime.now()): Long {
    if (startAtMinute !in 0 until 24 * 60) return 0
    var at = now.toLocalDate().atTime(LocalTime.of(startAtMinute / 60, startAtMinute % 60))
    if (!at.isAfter(now)) at = at.plusDays(1)
    return Duration.between(now, at).toMillis()
  }
}

/** "HH:mm" for a minute of the day. */
fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** Parses "H:mm" / "HH:mm" into a minute of the day; blank or invalid gives -1. */
fun parseMinute(text: String): Int {
  val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return -1
  val h = m.groupValues[1].toInt()
  val min = m.groupValues[2].toInt()
  return if (h in 0..23 && min in 0..59) h * 60 + min else -1
}
