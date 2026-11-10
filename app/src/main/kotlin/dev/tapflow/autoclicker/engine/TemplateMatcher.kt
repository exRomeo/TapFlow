package dev.tapflow.autoclicker.engine

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class MatchResult(val centerX: Float, val centerY: Float, val score: Double)

/**
 * Finds a template image on a screenshot with OpenCV normalized cross-correlation.
 * Both images are converted to grayscale and searched at half resolution for speed.
 */
object TemplateMatcher {
  private const val SCALE = 0.5

  private val ready: Boolean by lazy { OpenCVLoader.initLocal() }

  /** Best match of [template] inside [screen], or null if OpenCV is unavailable. */
  fun find(screen: Bitmap, template: Bitmap): MatchResult? {
    if (!ready) return null
    val screenGray = toScaledGray(screen)
    val templateGray = toScaledGray(template)
    val result = Mat()
    try {
      if (templateGray.cols() > screenGray.cols() || templateGray.rows() > screenGray.rows()) return null
      Imgproc.matchTemplate(screenGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED)
      val best = Core.minMaxLoc(result)
      val cx = (best.maxLoc.x + templateGray.cols() / 2.0) / SCALE
      val cy = (best.maxLoc.y + templateGray.rows() / 2.0) / SCALE
      return MatchResult(cx.toFloat(), cy.toFloat(), best.maxVal)
    } finally {
      screenGray.release()
      templateGray.release()
      result.release()
    }
  }

  private fun toScaledGray(bitmap: Bitmap): Mat {
    val rgba = Mat()
    Utils.bitmapToMat(bitmap, rgba)
    val gray = Mat()
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    rgba.release()
    val scaled = Mat()
    Imgproc.resize(gray, scaled, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
    gray.release()
    return scaled
  }
}
