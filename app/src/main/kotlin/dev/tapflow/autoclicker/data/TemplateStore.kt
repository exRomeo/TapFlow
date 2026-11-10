package dev.tapflow.autoclicker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/** Stores the small images that image steps look for, as PNGs in app-private storage. */
class TemplateStore(context: Context) {
  private val dir = File(context.filesDir, "templates").apply { mkdirs() }
  private val cache = HashMap<String, Bitmap>()

  /** Saves [bitmap] and returns its file name, which is what scripts reference. */
  fun save(bitmap: Bitmap): String {
    val name = "${UUID.randomUUID()}.png"
    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    cache[name] = bitmap
    return name
  }

  fun load(name: String): Bitmap? =
    cache[name] ?: File(dir, name).takeIf { it.exists() }
      ?.let { BitmapFactory.decodeFile(it.path) }
      ?.also { cache[name] = it }
}
