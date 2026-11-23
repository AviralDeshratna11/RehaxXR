package com.creatofit.motion.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.creatofit.motion.core.Coordinates
import com.creatofit.motion.tracking.TrackingController

/** Skeleton drawn over the camera preview (PreviewView FILL_CENTER), mirrored like the preview. */
class LandmarkOverlay(context: Context, private val controller: TrackingController) : View(context) {
    var mirrored = true
    var enabledOverlay = true
    private val density = resources.displayMetrics.density
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND }
    private val boneShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000; strokeWidth = 5f * density; strokeCap = Paint.Cap.ROUND }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt() }
    private val jointRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val lines = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28,
        27 to 29, 29 to 31, 27 to 31, 28 to 30, 30 to 32, 28 to 32, 15 to 19, 16 to 20)
    private val joints = listOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = controller.latest.get()?.pose
        if (enabledOverlay && !controller.demo && frame != null && SystemClock.uptimeMillis() - frame.capturedMs < controller.uncertaintyMs) {
            val points = frame.image.map { Coordinates.overlayFill(it.p, width.toFloat(), height.toFloat(), frame.width, frame.height, mirrored) }
            fun visible(i: Int) = frame.observedJoints?.getOrNull(i) != false && frame.image[i].confidence >= frame.scoreFloor
            for ((a, b) in lines) if (visible(a) && visible(b))
                canvas.drawLine(points[a].first, points[a].second + density, points[b].first, points[b].second + density, boneShadow)
            for ((a, b) in lines) if (visible(a) && visible(b))
                canvas.drawLine(points[a].first, points[a].second, points[b].first, points[b].second, bone)
            for (i in joints) if (visible(i)) {
                canvas.drawCircle(points[i].first, points[i].second, 5f * density, jointRing)
                canvas.drawCircle(points[i].first, points[i].second, 3.4f * density, joint)
            }
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
