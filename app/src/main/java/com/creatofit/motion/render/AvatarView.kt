package com.creatofit.motion.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import com.creatofit.motion.core.*
import com.creatofit.motion.tracking.TrackingController
import com.creatofit.motion.tracking.TrackingSnapshot
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.*

/** Heat ramp shared by the 3D highlights, labels and the muscle list: amber -> orange -> red. */
object Heat {
    private val stops = listOf(0f to intArrayOf(0xFF, 0xC4, 0x4D), .5f to intArrayOf(0xFF, 0x7A, 0x3D), 1f to intArrayOf(0xE0, 0x2F, 0x3B))
    fun rgb(level: Float): IntArray {
        val l = level.coerceIn(0f, 1f)
        val (a, b) = stops.zipWithNext().first { l <= it.second.first }
        val t = (l - a.first) / (b.first - a.first)
        return IntArray(3) { (a.second[it] + (b.second[it] - a.second[it]) * t).roundToInt() }
    }
    fun argb(level: Float): Int { val c = rgb(level); return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2] }
}

class AvatarView(context: Context, private val controller: TrackingController) : TextureView(context), Choreographer.FrameCallback {
    companion object { init { Utils.init() } }
    private data class Region(val segment: String, val anchor: V3, val normal: V3)
    private class Pose(var q: Q, var p: V3)

    private val scene = AnatomyScene(this)
    var labels: LabelOverlay? = null
    var musclesVisible = true
    var running = true
    private var attached = false
    private val entities = mutableMapOf<String, Int>()
    private val regionMaterials = mutableMapOf<String, com.google.android.filament.MaterialInstance>()
    private val regions: Map<String, Region>
    private val segmentNames = Seg.entries.associateBy { it.node }
    private val display = mutableMapOf<Seg, Pose>()
    private val regionLevel = mutableMapOf<String, Float>()
    private var lastFrameMs = 0L
    private var idle: TrackingSnapshot? = null
    // Framing & orbit.
    private var center = V3(0f, .9f, 0f); private var halfH = 1.0f; private var halfW = .5f
    private var userYaw = 0f; private var userPitch = 0f
    private var mirrored = true

    init {
        isOpaque = true
        val bytes = context.assets.open("avatar.glb").use { it.readBytes() }
        scene.loadModelGlb(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
        scene.asset?.let { asset -> asset.entities.forEach { e -> asset.getName(e)?.let { entities[it] = e } } }
        val json = JSONObject(context.assets.open("avatar_regions.json").bufferedReader().use { it.readText() })
        regions = json.keys().asSequence().associateWith { k ->
            val o = json.getJSONObject(k)
            fun v(name: String) = o.getJSONArray(name).let { V3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
            Region(o.getString("segment"), v("anchor"), v("normal"))
        }
        scene.onRenderableAdded = { e ->
            val rm = scene.engine.renderableManager
            val i = rm.getInstance(e)
            if (i != 0) {
                val name = scene.asset?.getName(e) ?: ""
                val isRegion = name in regions
                rm.setCastShadows(i, !isRegion && name != "floor")
                rm.setReceiveShadows(i, true)
            }
        }
    }

    // ---- Touch: drag to orbit, double tap to reset -------------------------------------------------
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            userYaw = (userYaw - dx * .45f) % 360f
            userPitch = (userPitch + dy * .3f).coerceIn(-25f, 55f)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
    })
    val isOrbited get() = abs(userYaw) > 1f || abs(userPitch) > 1f
    fun resetView() { userYaw = 0f; userPitch = 0f }
    fun turnAround() { userYaw = if (abs(userYaw) > 90f) 0f else 180f }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; Choreographer.getInstance().postFrameCallback(this) }
    override fun onDetachedFromWindow() { attached = false; Choreographer.getInstance().removeFrameCallback(this); scene.destroy(); super.onDetachedFromWindow() }

    private fun idleSnapshot(): TrackingSnapshot {
        idle?.let { return it }
        val pipeline = com.creatofit.motion.tracking.MotionPipeline()
        var s: TrackingSnapshot? = null
        for (i in 0 until 3) { val o = PoseSynth.frame(PoseSynth.standing(), 1000L + i * 33); s = pipeline.process(o.frame, o.upCamera, false) }
        return s!!.copy(analysis = MuscleAnalysis.EMPTY).also { idle = it }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) .016f else ((now - lastFrameMs) / 1000f).coerceIn(.001f, .1f)
        lastFrameMs = now
        val live = controller.displaySnapshot(now)
        val age = live?.let { now - it.pose.capturedMs } ?: Long.MAX_VALUE
        val fresh = live != null && (running || controller.demo) && age < controller.lostTimeoutMs
        val snap = if (fresh) live!! else idleSnapshot()
        val body = snap.body
        mirrored = if (controller.demo) false else snap.mirrored

        // ---- Segment transforms (exponential smoothing; ~45 ms lag) --------------------------------
        val a = 1 - exp(-dt / .045f)
        val tm = scene.engine.transformManager
        for (seg in Seg.entries) {
            val basis = body.frame(seg); val origin = body.origins.getValue(seg)
            val target = basis.quaternion()
            val pose = display.getOrPut(seg) { Pose(target, origin) }
            pose.q = pose.q.slerp(target, a); pose.p = pose.p.lerp(origin, a)
            entities[seg.node]?.let { tm.setTransform(tm.getInstance(it), pose.q.matrix(pose.p, V3(1f, 1f, 1f))) }
        }

        // ---- Muscle highlight colours ----------------------------------------------------------------
        val levels = if (musclesVisible && fresh && age < controller.uncertaintyMs) snap.analysis.regions else emptyMap()
        val fade = 1 - exp(-dt / .12f)
        for ((name, _) in regions) {
            val material = regionMaterials[name] ?: entities[name]?.let { e ->
                val rm = scene.engine.renderableManager; val i = rm.getInstance(e)
                if (i == 0) null else rm.getMaterialInstanceAt(i, 0).also { regionMaterials[name] = it }
            } ?: continue
            val cur = regionLevel[name] ?: 0f
            val next = cur + ((levels[name] ?: 0f) - cur) * fade
            regionLevel[name] = next
            val c = Heat.rgb(next)
            fun lin(v: Int) = (v / 255f).pow(2.2f)
            val alpha = (next / .18f).coerceIn(0f, 1f) * .92f
            material.setParameter("baseColorFactor", lin(c[0]), lin(c[1]), lin(c[2]), alpha)
        }

        // ---- Camera: the real camera's viewpoint, optionally orbited ---------------------------------
        val pts = body.allPoints() + (body.headCenter + body.frame(Seg.HEAD).y * .15f)
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = min(0f, pts.minOf { it.y }); val maxY = pts.maxOf { it.y }
        val minZ = pts.minOf { it.z }; val maxZ = pts.maxOf { it.z }
        // Lower the look-at point a little so the feet clear the legend at the bottom of the card.
        val targetCenter = V3((minX + maxX) / 2, (minY + maxY) / 2 - .10f, (minZ + maxZ) / 2)
        val radiusXZ = max(maxX - minX, maxZ - minZ) / 2
        // Margins cover the avatar's volume beyond its skeleton points.
        val targetH = (maxY - minY) / 2 + .24f
        val targetW = radiusXZ + .24f
        // Zoom out quickly (never crop the body), zoom in gently.
        fun ease(cur: Float, target: Float) = cur + (target - cur) * (1 - exp(-dt / if (target > cur) .12f else .6f))
        center = center.lerp(targetCenter, 1 - exp(-dt / .25f)); halfH = ease(halfH, targetH); halfW = ease(halfW, targetW)
        var viewDir = V3(body.cameraZ.x, 0f, body.cameraZ.z).let { if (it.length() < .2f) V3(0f, 0f, 1f) else it.unit() }
        val camPitch = Math.toDegrees(asin(body.cameraZ.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        viewDir = rotate(viewDir, V3.UP, Math.toRadians(userYaw.toDouble()).toFloat())
        val pitch = Math.toRadians((camPitch + 8f + userPitch).coerceIn(-30f, 70f).toDouble()).toFloat()
        viewDir = (viewDir * cos(pitch) + V3.UP * sin(pitch)).unit()
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val fov = 30f
        val tanHalf = tan(Math.toRadians(fov / 2.0)).toFloat()
        val dist = max(halfH / tanHalf, halfW / (tanHalf * aspect.coerceAtLeast(.2f))) * 1.06f + radiusXZ * .5f
        val eye = center + viewDir * dist
        entities["floor"]?.let { e ->
            val scale = max(1f, (radiusXZ + .25f) / .62f)
            tm.setTransform(tm.getInstance(e), Q.IDENTITY.matrix(V3(center.x, -.002f, center.z), V3(scale, 1f, scale)))
        }
        val near = .05; val far = 30.0
        val proj = perspective(fov, aspect, near.toFloat(), far.toFloat(), mirrored)
        scene.camera.setCustomProjection(proj.map { it.toDouble() }.toDoubleArray(), near, far)
        scene.camera.lookAt(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(), center.x.toDouble(), center.y.toDouble(), center.z.toDouble(), 0.0, 1.0, 0.0)
        scene.view.isFrontFaceWindingInverted = mirrored

        // ---- Callouts ---------------------------------------------------------------------------------
        labels?.let { overlay ->
            val callouts = if (levels.isEmpty()) emptyList() else buildCallouts(snap.analysis, eye, center, proj)
            overlay.update(callouts)
        }

        if ((running || controller.demo) && scene.render(frameTimeNanos)) controller.metrics.render(now, if (fresh && !controller.demo) snap.pose else null)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun buildCallouts(analysis: MuscleAnalysis, eye: V3, target: V3, proj: FloatArray): List<Callout> {
        val view = lookAt(eye, target)
        fun project(p: V3): PointF? {
            val v = mul(view, p, 1f); val c = mul4(proj, v)
            if (c[3] <= 1e-4f) return null
            return PointF((c[0] / c[3] + 1) / 2 * width, (1 - c[1] / c[3]) / 2 * height)
        }
        val used = mutableSetOf<String>()
        val out = mutableListOf<Callout>()
        val widthDp = width / resources.displayMetrics.density
        val maxLabels = when { widthDp < 220f -> 3; widthDp < 300f -> 4; else -> 6 }
        for (entry in analysis.entries) {
            if (out.size >= maxLabels) break
            val region = entry.muscle.region ?: continue
            if (region in used) continue
            val anchors = mutableListOf<PointF>(); var behind = 0; var count = 0
            for (side in entry.sides) {
                val name = region + if (side == Side.LEFT) "_l" else "_r"
                val r = regions[name] ?: continue
                val seg = segmentNames[r.segment] ?: continue
                val pose = display[seg] ?: continue
                val basis = pose.q.basis()
                val world = pose.p + basis.toWorld(r.anchor)
                val normal = basis.toWorld(r.normal)
                project(world)?.let { anchors += it; count++ }
                if (normal.dot(eye - world) < 0) behind++
            }
            if (anchors.isEmpty()) continue
            used += region
            out += Callout(entry.muscle.name, entry.muscle.short,
                "${entry.intensity.label} · ${entry.contraction.label.lowercase()}",
                Heat.argb(entry.level), anchors, behind == count, entry.level)
        }
        return out
    }

    // ---- Matrix helpers (column-major, OpenGL conventions) ----------------------------------------------
    private fun perspective(fovY: Float, aspect: Float, n: Float, f: Float, mirror: Boolean): FloatArray {
        val t = 1f / tan(Math.toRadians(fovY / 2.0)).toFloat()
        val m = FloatArray(16)
        m[0] = (t / aspect) * if (mirror) -1f else 1f
        m[5] = t
        m[10] = -(f + n) / (f - n); m[11] = -1f
        m[14] = -2f * f * n / (f - n)
        return m
    }
    private fun lookAt(eye: V3, target: V3): FloatArray {
        val f = (target - eye).unit(); val s = f.cross(V3.UP).unit(); val u = s.cross(f)
        return floatArrayOf(s.x, u.x, -f.x, 0f, s.y, u.y, -f.y, 0f, s.z, u.z, -f.z, 0f,
            -s.dot(eye), -u.dot(eye), f.dot(eye), 1f)
    }
    private fun mul(m: FloatArray, p: V3, w: Float) = floatArrayOf(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12] * w, m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13] * w,
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14] * w, m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15] * w)
    private fun mul4(m: FloatArray, v: FloatArray) = FloatArray(4) { r -> m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3] }
}
