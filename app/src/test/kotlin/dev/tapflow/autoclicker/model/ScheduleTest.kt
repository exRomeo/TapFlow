package dev.tapflow.autoclicker.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ScheduleTest {
  private val now = LocalDateTime.of(2026, 11, 10, 14, 30, 0)

  @Test fun parsesValidTimes() {
    assertEquals(0, parseMinute("0:00"))
    assertEquals(14 * 60 + 5, parseMinute("14:05"))
    assertEquals(23 * 60 + 59, parseMinute(" 23:59 "))
  }

  @Test fun rejectsInvalidTimes() {
    assertEquals(-1, parseMinute(""))
    assertEquals(-1, parseMinute("24:00"))
    assertEquals(-1, parseMinute("12:60"))
    assertEquals(-1, parseMinute("noon"))
  }

  @Test fun formatsMinute() {
    assertEquals("07:05", formatMinute(7 * 60 + 5))
  }

  @Test fun notScheduledStartsNow() {
    assertEquals(0, Script().millisUntilScheduledStart(now))
  }

  @Test fun laterTodayWaitsUntilThen() {
    val s = Script(startAtMinute = 15 * 60)
    assertEquals(30 * 60_000L, s.millisUntilScheduledStart(now))
  }

  @Test fun pastTimeWaitsUntilTomorrow() {
    val s = Script(startAtMinute = 14 * 60 + 30)
    assertEquals(24 * 60 * 60_000L, s.millisUntilScheduledStart(now))
  }
}
