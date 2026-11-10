package dev.tapflow.autoclicker.data

import android.content.Context
import dev.tapflow.autoclicker.model.Script
import kotlinx.serialization.json.Json
import java.io.File

/** Stores each script as a JSON file in app-private storage. Nothing leaves the device. */
class ScriptStore(context: Context) {
  private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  fun list(): List<Script> =
    dir.listFiles { f -> f.extension == "json" }
      .orEmpty()
      .mapNotNull { f -> runCatching { json.decodeFromString<Script>(f.readText()) }.getOrNull() }
      .sortedByDescending { it.updatedAt }

  fun save(script: Script): Script {
    val stamped = script.copy(updatedAt = System.currentTimeMillis())
    File(dir, "${stamped.id}.json").writeText(json.encodeToString(stamped))
    return stamped
  }

  fun delete(id: String) {
    File(dir, "$id.json").delete()
  }
}
