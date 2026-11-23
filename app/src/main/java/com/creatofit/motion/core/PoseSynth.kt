package com.creatofit.motion.core

import kotlin.math.*

/**
 * Synthetic MediaPipe-style landmarks from segment directions. Used by unit tests and the in-app demo.
 * Directions are given in the body frame as (right, up, forward) components; arm and leg
 * directions are specified for the RIGHT side and mirrored for the left unless given separately.
 */
data class SynthPose(
    val trunk: V3 = V3(0f, 1f, 0f),
    val upperArmR: V3 = V3(.12f, -1f, 0f), val forearmR: V3 = V3(.05f, -1f, .1f),
    val thighR: V3 = V3(.04f, -1f, 0f), val shankR: V3 = V3(.02f, -1f, 0f), val footR: V3 = V3(0f, 0f, 1f),
    val upperArmL: V3? = null, val forearmL: V3? = null, val thighL: V3? = null, val shankL: V3? = null, val footL: V3? = null,
    /** Whole-body pitch in degrees (90 = horizontal, face down). */
    val pitch: Float = 0f,
    /** Whole-body yaw in degrees (0 = facing the camera, 90 = right side toward the camera). */
    val yaw: Float = 0f,
    /** Head direction (body frame); defaults to the trunk. */
    val headUp: V3? = null,
    /** Choose the pitch so that the hands/forearms and the toes rest on the same floor (planks, push-ups). */
    val autoLevel: Boolean = false,
)

object PoseSynth {
    data class Output(val frame: PoseFrame, val upCamera: V3)

    fun dir(degFromDown: Float, towardForward: Float = 0f, towardOut: Float = 0f): V3 {
        // Start pointing down, rotate toward forward then outward.
        var v = V3(0f, -1f, 0f)
        v = tilt(v, V3(0f, 0f, 1f), degFromDown)
        if (towardOut != 0f) v = tilt(v, V3(1f, 0f, 0f), towardOut)
        if (towardForward != 0f) v = tilt(v, V3(0f, 0f, 1f), towardForward)
        return v.unit()
    }

    private fun mirror(v: V3) = V3(-v.x, v.y, v.z)

    /**
     * @param cameraTilt phone pitch in degrees (positive = camera tilted upward).
     */
    fun frame(p: SynthPose, time: Long, width: Int = 640, height: Int = 480, cameraTilt: Float = 0f,
              hidden: Set<Int> = emptySet(), id: Long = time): Output {
        // Body-frame skeleton (x = subject right, y = up, z = forward); converted to camera space later.
        val R = V3(1f, 0f, 0f); val F = V3(0f, 0f, 1f)
        val trunk = p.trunk.unit()
        val right = R.orthoTo(trunk)
        val fwd = trunk.cross(right).let { if (it.dot(F) < 0) -it else it }.unit() // forward, perpendicular to trunk
        val pts = Array(33) { V3.ZERO }
        val shoulderCenter = trunk * .50f
        val headUp = (p.headUp ?: trunk).unit()
        val neckTop = shoulderCenter + trunk * .10f
        val head = neckTop + headUp * .11f
        val headFwd = fwd.orthoTo(headUp)
        val headRight = headUp.cross(headFwd).unit().let { if (it.dot(R) < 0) -it else it }
        pts[0] = head + headFwd * .10f - headUp * .015f
        for (i in 1..6) { val s = if (i <= 3) -1f else 1f; pts[i] = head + headFwd * .085f + headUp * .025f + headRight * (s * .02f * ((i - 1) % 3 + 1)) }
        pts[7] = head - headRight * .075f; pts[8] = head + headRight * .075f
        pts[9] = pts[0] - headUp * .04f - headRight * .02f; pts[10] = pts[0] - headUp * .04f + headRight * .02f
        for (side in 0..1) {
            val sign = if (side == 0) -1f else 1f
            fun pick(r: V3, l: V3?) = if (side == 1) r.unit() else (l ?: mirror(r)).unit()
            val ua = pick(p.upperArmR, p.upperArmL); val fa = pick(p.forearmR, p.forearmL)
            val th = pick(p.thighR, p.thighL); val sh = pick(p.shankR, p.shankL); val ft = pick(p.footR, p.footL)
            val shoulder = shoulderCenter + right * (sign * .185f) - trunk * .03f
            val elbow = shoulder + ua * .29f
            val wrist = elbow + fa * .26f
            val lateral = (right * sign).orthoTo(fa)
            pts[11 + side] = shoulder; pts[13 + side] = elbow; pts[15 + side] = wrist
            pts[17 + side] = wrist + fa * .08f - lateral * .03f   // pinky
            pts[19 + side] = wrist + fa * .085f + lateral * .02f  // index
            pts[21 + side] = wrist + fa * .03f + lateral * .035f  // thumb
            val hip = V3.ZERO + right * (sign * .09f)
            val knee = hip + th * .43f
            val ankle = knee + sh * .42f
            val footUp = (-sh).orthoTo(ft)
            pts[23 + side] = hip; pts[25 + side] = knee; pts[27 + side] = ankle
            pts[29 + side] = ankle - footUp * .075f - ft * .05f
            pts[31 + side] = ankle - footUp * .06f + ft * .19f
        }
        // Body frame -> camera space (x right, y up, z toward camera) for a subject facing the camera.
        val pitchDeg = if (p.autoLevel) levelPitch(pts, p) else p.pitch
        val pitchR = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val yawR = Math.toRadians(p.yaw.toDouble()).toFloat()
        val tiltR = Math.toRadians(cameraTilt.toDouble()).toFloat()
        fun toCamera(v: V3): V3 {
            var c = V3(-v.x, v.y, v.z)                 // subject's right appears on the image left
            c = rotate(c, V3(1f, 0f, 0f), pitchR)      // pitch forward: up -> toward camera
            c = rotate(c, V3(0f, 1f, 0f), yawR)
            c = rotate(c, V3(1f, 0f, 0f), -tiltR)      // camera tilted up: the scene rotates down in view
            return c
        }
        val cam = pts.map(::toCamera)
        val hipCenter = (cam[23] + cam[24]) * .5f
        val upCamera = rotate(V3.UP, V3(1f, 0f, 0f), -tiltR)
        val minX = cam.minOf { it.x }; val maxX = cam.maxOf { it.x }
        val minY = cam.minOf { it.y }; val maxY = cam.maxOf { it.y }
        val scale = .78f * height / max(maxY - minY, (maxX - minX) * height / width).coerceAtLeast(.3f)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val image = cam.mapIndexed { i, c ->
            Landmark(V3((width / 2f + (c.x - cx) * scale) / width, (height / 2f - (c.y - cy) * scale) / height, -c.z * .3f),
                if (i in hidden) .05f else .98f)
        }
        val world = cam.mapIndexed { i, c -> val r = c - hipCenter; Landmark(V3(r.x, -r.y, -r.z), if (i in hidden) .05f else .98f) }
        return Output(PoseFrame(id, time, time, image, world, width, height), upCamera)
    }

    /** Pitch (degrees) at which the lowest hand/forearm support and the lowest toe are level. */
    private fun levelPitch(pts: Array<V3>, p: SynthPose): Float {
        fun gap(deg: Float): Float {
            val r = Math.toRadians(deg.toDouble()).toFloat()
            fun h(v: V3) = rotate(V3(-v.x, v.y, v.z), V3(1f, 0f, 0f), r).y
            val upper = (0..1).flatMap { s ->
                val fa = (pts[15 + s] - pts[13 + s]).unit()
                listOf(h(pts[15 + s] + fa * .085f) - .03f, h(pts[13 + s]) - .04f)
            }.min()
            val toes = (0..1).map { s ->
                val ft = (pts[31 + s] - pts[29 + s]).unit(); val ankle = pts[27 + s]
                val shank = (pts[27 + s] - pts[25 + s]).unit()
                h(ankle - (-shank).orthoTo(ft) * .075f + ft * .2f)
            }.min()
            return upper - toes
        }
        var best = p.pitch; var bestGap = Float.MAX_VALUE
        var d = 40f
        while (d <= 100f) { val g = kotlin.math.abs(gap(d)); if (g < bestGap) { bestGap = g; best = d }; d += .25f }
        return best
    }

    // ---- Reference poses -----------------------------------------------------------------------
    fun standing() = SynthPose()
    fun squat(depth: Float = 1f): SynthPose {
        val thigh = 85f * depth; val knee = 100f * depth; val lean = 38f * depth
        return SynthPose(trunk = tilt(V3.UP, V3(0f, 0f, 1f), lean),
            thighR = dir(thigh, towardOut = 12f * depth), shankR = dir(thigh - knee, towardOut = 6f * depth),
            upperArmR = dir(80f * depth + 5f), forearmR = dir(85f * depth + 5f))
    }
    fun hinge() = SynthPose(trunk = tilt(V3.UP, V3(0f, 0f, 1f), 55f), thighR = dir(18f), shankR = dir(0f), upperArmR = dir(2f), forearmR = dir(2f))
    fun lateralRaise(angle: Float = 90f) = SynthPose(upperArmR = dir(0f, towardOut = angle), forearmR = dir(0f, towardOut = angle))
    fun frontRaise(angle: Float = 90f) = SynthPose(upperArmR = dir(angle), forearmR = dir(angle))
    fun curl(elbow: Float = 90f) = SynthPose(upperArmR = dir(5f), forearmR = dir(5f + elbow))
    fun overhead() = SynthPose(upperArmR = dir(175f, towardOut = 10f), forearmR = dir(178f, towardOut = 10f))
    fun calfRaise() = SynthPose(footR = tilt(V3(0f, 0f, 1f), V3(0f, -1f, 0f), 35f))
    fun singleLeg() = SynthPose(thighR = dir(0f, towardOut = -6f), shankR = dir(0f, towardOut = -6f), thighL = mirror(dir(80f)), shankL = mirror(dir(-10f)))
    fun lunge() = SynthPose(trunk = tilt(V3.UP, V3(0f, 0f, 1f), 8f),
        thighR = dir(80f), shankR = dir(-5f), thighL = mirror(dir(-25f)), shankL = mirror(dir(-100f)),
        footL = mirror(tilt(V3(0f, 0f, 1f), V3(0f, -1f, 0f), 50f)))
    /** Prone plank on the hands (side view). */
    fun highPlank() = SynthPose(pitch = 90f, autoLevel = true, yaw = 90f, trunk = tilt(V3.UP, V3(0f, 0f, 1f), -3f),
        upperArmR = dir(90f), forearmR = dir(90f), thighR = dir(-3f), shankR = dir(-3f), footR = dir(90f))
    fun pushUpBottom() = SynthPose(pitch = 80f, autoLevel = true, yaw = 90f,
        upperArmR = dir(-30f, towardOut = 35f), forearmR = dir(118f, towardOut = 10f), thighR = dir(0f), shankR = dir(0f), footR = dir(100f))
    fun forearmPlank() = SynthPose(pitch = 84f, autoLevel = true, yaw = 90f,
        upperArmR = dir(85f), forearmR = dir(175f), thighR = dir(0f), shankR = dir(0f), footR = dir(96f))

    /** Looping demo: squats, lateral raises, curls and push-ups, 4 s each. */
    fun demo(timeMs: Long, cameraTilt: Float = 0f): Output {
        val t = (timeMs % 16000L) / 1000f
        val phase = (t % 4f) / 4f
        val s = ((1 - cos(phase * 2 * PI)) / 2).toFloat() // 0 -> 1 -> 0
        val pose = when {
            t < 4f -> squat(s)
            t < 8f -> lateralRaise(10f + 80f * s)
            t < 12f -> curl(10f + 120f * s)
            else -> {
                val bottom = pushUpBottom(); val top = highPlank()
                SynthPose(pitch = 85f, yaw = 90f, autoLevel = true,
                    upperArmR = top.upperArmR.lerp(bottom.upperArmR, s).unit(), forearmR = top.forearmR.lerp(bottom.forearmR, s).unit(),
                    thighR = dir(0f), shankR = dir(0f), footR = top.footR.lerp(bottom.footR, s).unit())
            }
        }
        return frame(pose, timeMs, cameraTilt = cameraTilt)
    }
}
