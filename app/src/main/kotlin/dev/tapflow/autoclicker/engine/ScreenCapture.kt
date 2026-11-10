package dev.tapflow.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Image steps need AccessibilityService.takeScreenshot, added in Android 11. */
val imageStepsSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * Takes a full-resolution screenshot of the default display as a software bitmap, or null
 * on failure. Its pixels use the same coordinates as dispatched gestures. The system allows
 * roughly one screenshot every 333 ms; callers should poll slower than that.
 */
suspend fun AccessibilityService.captureScreen(): Bitmap? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
  return suspendCancellableCoroutine { cont ->
    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
      override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
        val buffer = result.hardwareBuffer
        val bitmap = try {
          Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
          }
        } finally {
          buffer.close()
        }
        if (cont.isActive) cont.resume(bitmap)
      }

      override fun onFailure(errorCode: Int) {
        Log.w("TapFlow", "screenshot failed, code $errorCode")
        if (cont.isActive) cont.resume(null)
      }
    })
  }
}
