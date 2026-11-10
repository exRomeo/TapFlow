package dev.tapflow.autoclicker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescribeTest {
  @Test fun tap() {
    assertEquals(
      "Tap at (720, 60), then wait 100 ms",
      describeStep(ClickTarget(ActionType.TAP, 720f, 60f)),
    )
  }

  @Test fun swipeWithRandomness() {
    val t = ClickTarget(
      ActionType.SWIPE, 10f, 20f, endX = 30f, endY = 40f, pressMs = 1500,
      delayAfterMs = 2000, randomDelayMs = 500, randomOffsetPx = 8, points = listOf(Pt(1f, 1f)),
    )
    assertEquals(
      "Swipe (10, 20) → (30, 40) in 1.5 s (recorded path), then wait 2 s, +0–500 ms random wait, ±8 px",
      describeStep(t),
    )
  }

  @Test fun imageStep() {
    val t = ClickTarget(ActionType.IMAGE, 0f, 0f, imageFiles = listOf("a", "b"), stopIfNotFound = true, delayAfterMs = 1000)
    assertEquals(
      "Find any of 2 images (85% match, up to 10 s, else stop script) and tap it, then wait 1 s",
      describeStep(t),
    )
  }

  @Test fun scriptSummary() {
    val lines = describeScript(
      Script(panelMode = PanelMode.SYNC, mode = RunMode.SYNC, loops = 5, maxDurationSec = 120, startAtMinute = 7 * 60 + 30),
    )
    assertEquals("Mode: Sync", lines[0])
    assertTrue("Steps: 0 (all at once)" in lines)
    assertTrue("Repeats: 5×" in lines)
    assertTrue("Stops after: 2 min" in lines)
    assertTrue("Starts at: 07:30" in lines)
  }
}
