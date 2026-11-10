package dev.tapflow.autoclicker.data

import dev.tapflow.autoclicker.model.ActionType
import dev.tapflow.autoclicker.model.ClickTarget
import dev.tapflow.autoclicker.model.Pt
import dev.tapflow.autoclicker.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store() = ScriptStore(tmp.root)

  @Test fun roundTripsAllFields() {
    val script = Script(
      name = "Daily",
      loops = 3,
      startAtMinute = 9 * 60,
      targets = listOf(
        ClickTarget(ActionType.SWIPE, 10f, 20f, endX = 30f, endY = 40f, points = listOf(Pt(15f, 25f))),
        ClickTarget(ActionType.IMAGE, 50f, 60f, imageFiles = listOf("a.png", "b.png"), stopIfNotFound = true),
      ),
    )
    val saved = store().save(script)
    val loaded = store().list().single()
    assertEquals(saved, loaded)
  }

  @Test fun corruptFileIsSkippedNotFatal() {
    val s = store()
    s.save(Script(name = "Good"))
    File(tmp.root, "broken.json").writeText("{ not json")
    assertEquals(listOf("Good"), s.list().map { it.name })
  }

  @Test fun saveLeavesNoTempFileAndOverwrites() {
    val s = store()
    val first = s.save(Script(name = "One"))
    s.save(first.copy(name = "Two"))
    assertEquals(listOf("Two"), s.list().map { it.name })
    assertFalse(tmp.root.listFiles()!!.any { it.name.endsWith(".tmp") })
  }

  @Test fun oldFilesWithoutNewFieldsStillLoad() {
    // A script saved by version 0.1 had no points, image or schedule fields.
    File(tmp.root, "old.json").writeText(
      """{"id":"old","name":"Legacy","targets":[{"type":"TAP","x":1.0,"y":2.0}]}"""
    )
    val loaded = store().list().single()
    assertEquals("Legacy", loaded.name)
    assertEquals(-1, loaded.startAtMinute)
    assertTrue(loaded.targets.single().imageFiles.isEmpty())
  }

  @Test fun deleteRemovesScript() {
    val s = store()
    val saved = s.save(Script(name = "Gone"))
    s.delete(saved.id)
    assertTrue(s.list().isEmpty())
  }
}
