package dev.tapflow.autoclicker.data

import android.content.Context
import dev.tapflow.autoclicker.model.Script
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stores each script as a JSON file in app-private storage. Nothing leaves the device.
 * Writes go to a temp file first and are then renamed, so a crash mid-save never leaves a
 * half-written script; an unreadable file is skipped instead of hiding the others.
 */
class ScriptStore(private val dir: File) {
  constructor(context: Context) : this(File(context.filesDir, "scripts"))

  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  init {
    dir.mkdirs()
  }

  fun list(): List<Script> =
    dir.listFiles { f -> f.extension == "json" }
      .orEmpty()
      .mapNotNull { f -> runCatching { json.decodeFromString<Script>(f.readText()) }.getOrNull() }
      .sortedByDescending { it.updatedAt }

  fun save(script: Script): Script {
    val stamped = script.copy(updatedAt = System.currentTimeMillis())
    writeAtomically(File(dir, "${stamped.id}.json"), json.encodeToString(stamped).toByteArray())
    return stamped
  }

  fun delete(id: String) {
    File(dir, "$id.json").delete()
  }
}

/** Writes [bytes] to a sibling temp file, then renames it over [target]. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
  val tmp = File(target.parentFile, "${target.name}.tmp")
  tmp.outputStream().use { out ->
    out.write(bytes)
    out.fd.sync()
  }
  if (!tmp.renameTo(target)) {
    // renameTo can't replace an existing file on some file systems.
    target.delete()
    check(tmp.renameTo(target)) { "could not save ${target.name}" }
  }
}
