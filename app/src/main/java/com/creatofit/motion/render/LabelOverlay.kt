package com.creatofit.motion.render

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One callout: a label box with leader lines to one or two anchor points on the avatar. */
data class Callout(val key: String, val title: String, val subtitle: String, val color: Int,
                   val anchors: List<PointF>, val behind: Boolean, val level: Float)

/**
 * Draws muscle callouts next to the 3D avatar: labels are stacked in left/right columns, sorted by
 * anchor height, separated so they never overlap, and eased between frames to avoid jitter.
 */
class LabelOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3E6EB.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000; maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111827.toInt(); textSize = dp(11.5f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt(); textSize = dp(10f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.3f); style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dash = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    private var callouts: List<Callout> = emptyList()
    private val eased = mutableMapOf<String, PointF>()
    private val path = Path()
    private val rect = RectF()
    private val shadowRect = RectF()
    var enabledLabels = true

    fun update(list: List<Callout>) { callouts = list; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledLabels || callouts.isEmpty() || width == 0) return
        val pad = dp(6f); val boxH = dp(34f); val gap = dp(5f)
        val maxW = min(dp(140f), width * .44f)
        val centerX = width / 2f
        // Column assignment by anchor position, balancing the columns.
        val sorted = callouts.sortedByDescending { it.level }
        val left = mutableListOf<Callout>(); val right = mutableListOf<Callout>()
        for (c in sorted) {
            val ax = c.anchors.minByOrNull { abs(it.x - centerX) }!!.x
            val preferLeft = c.anchors.any { it.x < centerX } && (c.anchors.size == 1 || ax <= centerX)
            val target = if (preferLeft) left else right
            val other = if (preferLeft) right else left
            if (target.size >= 4 && other.size < 4) other += c else target += c
        }
        fun layout(column: List<Callout>, isLeft: Boolean) {
            val items = column.sortedBy { c -> c.anchors.minOf { it.y } }
            val ys = items.map { c -> (c.anchors.minOf { it.y } - boxH / 2).coerceIn(pad, height - pad - boxH) }.toMutableList()
            for (i in 1 until ys.size) ys[i] = max(ys[i], ys[i - 1] + boxH + gap)
            val overflow = (ys.lastOrNull() ?: 0f) + boxH + pad - height
            if (overflow > 0) for (i in ys.indices.reversed()) {
                ys[i] -= overflow
                if (i > 0) ys[i - 1] = min(ys[i - 1], ys[i] - boxH - gap)
            }
            var previousBottom = -Float.MAX_VALUE
            items.forEachIndexed { i, c ->
                val w = min(maxW, max(titlePaint.measureText(c.title), subPaint.measureText(c.subtitle)) + dp(26f))
                val x = if (isLeft) pad else width - pad - w
                val target = PointF(x, ys[i].coerceAtLeast(pad))
                val p = eased.getOrPut(c.key) { PointF(target.x, target.y) }
                p.x += (target.x - p.x) * .35f; p.y += (target.y - p.y) * .35f
                // Easing must never make boxes in a column overlap.
                p.y = max(p.y, previousBottom + gap)
                previousBottom = p.y + boxH
                draw(canvas, c, p.x, p.y, w, boxH, isLeft)
            }
        }
        layout(left, true); layout(right, false)
        val keys = callouts.map { it.key }.toSet()
        eased.keys.retainAll(keys)
    }

    private fun draw(canvas: Canvas, c: Callout, x: Float, y: Float, w: Float, h: Float, isLeft: Boolean) {
        val edgeX = if (isLeft) x + w else x
        val edgeY = y + h / 2
        line.color = c.color; line.alpha = 220
        line.pathEffect = if (c.behind) dash else null
        dot.color = c.color
        for (a in c.anchors) {
            val elbowX = edgeX + (if (isLeft) dp(10f) else -dp(10f))
            path.reset(); path.moveTo(edgeX, edgeY); path.lineTo(elbowX, edgeY); path.lineTo(a.x, a.y)
            canvas.drawPath(path, line)
            canvas.drawCircle(a.x, a.y, dp(4.2f), ring)
            canvas.drawCircle(a.x, a.y, dp(3f), dot)
        }
        val r = rect.apply { set(x, y, x + w, y + h) }
        shadowRect.set(r.left, r.top + dp(2f), r.right, r.bottom + dp(2f))
        canvas.drawRoundRect(shadowRect, dp(9f), dp(9f), shadow)
        canvas.drawRoundRect(r, dp(9f), dp(9f), box)
        canvas.drawRoundRect(r, dp(9f), dp(9f), border)
        canvas.drawCircle(x + dp(11f), y + dp(13f), dp(3.5f), dot)
        canvas.save(); canvas.clipRect(r)
        canvas.drawText(ellipsize(c.title, titlePaint, w - dp(26f)), x + dp(19f), y + dp(16.5f), titlePaint)
        canvas.drawText(ellipsize(c.subtitle, subPaint, w - dp(14f)), x + dp(8f), y + dp(30f), subPaint)
        canvas.restore()
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }
}
