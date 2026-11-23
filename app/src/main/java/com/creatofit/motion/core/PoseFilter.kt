package com.creatofit.motion.core

import kotlin.math.*

/**
 * One Euro filter (Casiez et al., CHI 2012): a speed-adaptive low-pass filter.
 * Slow movement is smoothed strongly (no jitter); fast movement raises the cutoff (little lag).
 */
class OneEuro(private val minCutoff: Float, private val beta: Float, private val dCutoff: Float = 1.2f) {
    private var x = 0f
    private var dx = 0f
    private var primed = false
    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2f * PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }
    fun reset() { primed = false; dx = 0f }
    fun seed(value: Float) { x = value; dx = 0f; primed = true }
    fun filter(value: Float, dt: Float): Float {
        if (!primed) { seed(value); return value }
        val rawDx = (value - x) / dt
        dx += (rawDx - dx) * alpha(dCutoff, dt)
        val cutoff = minCutoff + beta * abs(dx)
        x += (value - x) * alpha(cutoff, dt)
        return x
    }
    val value get() = x
}

/**
 * Per-landmark One Euro filtering with confidence gating.
 * - Rejects out-of-order frames and resets after a long capture gap.
 * - A joint that drops below the score floor is held (confidence decays) for [holdMs], then released.
 * - A joint that returns after being lost is re-seeded at its measurement instead of being dragged from a stale value.
 */
class PoseFilter(private val holdMs: Long = 300) {
    private val n = 33
    private val image = Array(n) { arrayOf(OneEuro(1.6f, 6f), OneEuro(1.6f, 6f), OneEuro(1.2f, 2f)) }
    private val world = Array(n) { arrayOf(OneEuro(1.6f, 4f), OneEuro(1.6f, 4f), OneEuro(1.0f, 1.5f)) }
    private val lastSeen = LongArray(n) { -1 }
    private var previous: PoseFrame? = null

    fun reset() {
        previous = null; lastSeen.fill(-1)
        image.forEach { a -> a.forEach { it.reset() } }; world.forEach { a -> a.forEach { it.reset() } }
    }

    fun apply(frame: PoseFrame): PoseFrame? {
        if (frame.image.size != n || frame.world.size != n) return null
        val old = previous
        if (old != null && frame.capturedMs <= old.capturedMs) return null
        if (old != null && frame.capturedMs - old.capturedMs > 1000) reset()
        val prior = previous
        val dt = if (prior == null) .033f else ((frame.capturedMs - prior.capturedMs) / 1000f).coerceIn(.005f, .2f)
        val observed = BooleanArray(n)
        val outImage = ArrayList<Landmark>(n); val outWorld = ArrayList<Landmark>(n)
        for (i in 0 until n) {
            val im = frame.image[i]; val w = frame.world[i]
            val conf = min(im.confidence, w.confidence)
            val visible = im.p.finite() && w.p.finite() && conf.isFinite() && conf >= frame.scoreFloor &&
                im.p.x in -.02f..1.02f && im.p.y in -.02f..1.02f
            if (visible) {
                val returning = lastSeen[i] < 0 || frame.capturedMs - lastSeen[i] > holdMs
                if (returning) {
                    image[i][0].seed(im.p.x); image[i][1].seed(im.p.y); image[i][2].seed(im.p.z)
                    world[i][0].seed(w.p.x); world[i][1].seed(w.p.y); world[i][2].seed(w.p.z)
                }
                lastSeen[i] = frame.capturedMs
                observed[i] = true
                outImage += Landmark(V3(image[i][0].filter(im.p.x, dt), image[i][1].filter(im.p.y, dt), image[i][2].filter(im.p.z, dt)), conf)
                outWorld += Landmark(V3(world[i][0].filter(w.p.x, dt), world[i][1].filter(w.p.y, dt), world[i][2].filter(w.p.z, dt)), conf)
            } else {
                val age = if (lastSeen[i] < 0) Long.MAX_VALUE else frame.capturedMs - lastSeen[i]
                val held = prior != null && age < holdMs
                val heldConfidence = if (held) min(prior!!.image[i].confidence, prior.world[i].confidence) * .8f else 0f
                outImage += Landmark(if (held) prior!!.image[i].p else im.p, heldConfidence)
                outWorld += Landmark(if (held) prior!!.world[i].p else w.p, heldConfidence)
            }
        }
        val result = frame.copy(image = outImage, world = outWorld, observedJoints = observed.toList())
        previous = result
        return result
    }
}
