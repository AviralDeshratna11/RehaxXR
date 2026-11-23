package com.creatofit.motion.core

import kotlin.math.*

/**
 * Avatar / biomechanical skeleton proportions in metres (a 1.76 m adult).
 * Keep in sync with tools/generate_assets.py, which builds the avatar meshes on the same segment frames.
 */
object BodyDims {
    const val HIP_HALF = .09f
    const val SPINE = .50f          // pelvis centre -> shoulder centre (base of neck)
    const val SHOULDER_HALF = .185f
    const val SHOULDER_DROP = .03f
    const val NECK = .10f
    const val HEAD_CENTER = .11f    // neck top -> head centre
    const val UPPER_ARM = .29f
    const val FOREARM = .26f
    const val HAND = .17f
    const val THIGH = .43f
    const val SHANK = .42f
    const val ANKLE_HEIGHT = .075f  // ankle joint above the sole
    const val HEEL_BACK = .05f      // heel behind ankle
    const val TOE_FORWARD = .20f    // toe tip ahead of ankle
}

enum class Side(val label: String) { LEFT("Left"), RIGHT("Right") }

/** Rigid avatar segments. Each has a frame: Y along the segment (proximal to distal), Z anterior, X = Y x Z. */
enum class Seg(val node: String) {
    PELVIS("pelvis"), CHEST("chest"), NECK("neck"), HEAD("head"),
    UPPER_ARM_L("upper_arm_l"), UPPER_ARM_R("upper_arm_r"), FOREARM_L("forearm_l"), FOREARM_R("forearm_r"),
    HAND_L("hand_l"), HAND_R("hand_r"), THIGH_L("thigh_l"), THIGH_R("thigh_r"),
    SHANK_L("shank_l"), SHANK_R("shank_r"), FOOT_L("foot_l"), FOOT_R("foot_r");
    companion object {
        fun upperArm(s: Side) = if (s == Side.LEFT) UPPER_ARM_L else UPPER_ARM_R
        fun forearm(s: Side) = if (s == Side.LEFT) FOREARM_L else FOREARM_R
        fun hand(s: Side) = if (s == Side.LEFT) HAND_L else HAND_R
        fun thigh(s: Side) = if (s == Side.LEFT) THIGH_L else THIGH_R
        fun shank(s: Side) = if (s == Side.LEFT) SHANK_L else SHANK_R
        fun foot(s: Side) = if (s == Side.LEFT) FOOT_L else FOOT_R
    }
}

enum class Chain { TORSO, HEAD, ARM_L, ARM_R, LEG_L, LEG_R;
    companion object {
        fun arm(s: Side) = if (s == Side.LEFT) ARM_L else ARM_R
        fun leg(s: Side) = if (s == Side.LEFT) LEG_L else LEG_R
    }
}

data class SidePoints(val shoulder: V3, val elbow: V3, val wrist: V3, val handTip: V3,
    val hip: V3, val knee: V3, val ankle: V3, val heel: V3, val toe: V3)

/** Solved full-body pose in a gravity-aligned world frame (Y up, floor at y=0, metres). */
data class BodyState(
    val time: Long,
    val frames: Map<Seg, Basis>,
    val origins: Map<Seg, V3>,
    val pelvis: V3, val neckBase: V3, val headCenter: V3,
    val left: SidePoints, val right: SidePoints,
    val tracked: Set<Chain>,
    /** Camera-space axes (x right, y up, z toward the viewer) expressed in world coordinates. */
    val cameraX: V3, val cameraY: V3, val cameraZ: V3,
    val hasDepth: Boolean,
) {
    fun side(s: Side) = if (s == Side.LEFT) left else right
    fun frame(s: Seg) = frames.getValue(s)
    /** Subject's right, up and forward directions of the pelvis and chest. */
    val pelvisRight get() = -frame(Seg.PELVIS).x
    val chestRight get() = -frame(Seg.CHEST).x
    val trunkUp get() = frame(Seg.CHEST).y
    val chestForward get() = frame(Seg.CHEST).z
    val pelvisForward get() = frame(Seg.PELVIS).z
    fun isTracked(c: Chain) = c in tracked
    fun allPoints(): List<V3> = listOf(pelvis, neckBase, headCenter + V3(0f, .13f, 0f)) +
        listOf(left, right).flatMap { listOf(it.shoulder, it.elbow, it.wrist, it.handTip, it.hip, it.knee, it.ankle, it.heel, it.toe) }
}

/**
 * Converts filtered landmarks into a fixed-proportion 3D skeleton.
 *
 * Bone directions combine the image-plane direction (precise, matches the video exactly) with the
 * out-of-plane depth component of MediaPipe world landmarks. Fixed bone lengths keep the avatar's
 * proportions stable. The phone's gravity vector levels the floor. Chains that leave the frame hold
 * their last pose relative to the body and relax toward a neutral pose.
 */
class BodySolver {
    // Virtual landmark indices appended after the 33 MediaPipe landmarks.
    private companion object {
        const val MID_HIP = 33; const val MID_SHOULDER = 34; const val MID_EAR = 35
        const val HAND_L = 36; const val HAND_R = 37
        const val HOLD_MS = 1200L
    }
    private val lastLocal = mutableMapOf<String, V3>()
    private val lastSeen = mutableMapOf<String, Long>()
    private var lastElbowAxis = mutableMapOf<Side, V3>()
    private var floorOffset: Float? = null
    private var last: BodyState? = null
    private var lastTime = -1L

    fun reset() { lastLocal.clear(); lastSeen.clear(); lastElbowAxis.clear(); floorOffset = null; last = null; lastTime = -1 }

    /**
     * @param upCamera gravity "up" in camera space (x right, y up, z toward camera), unit length.
     */
    fun solve(frame: PoseFrame, upCamera: V3 = V3.UP): BodyState? {
        if (lastTime >= 0 && frame.capturedMs - lastTime > 2000) reset()
        lastTime = frame.capturedMs
        val aspect = frame.width.toFloat() / frame.height.coerceAtLeast(1)
        val floor = frame.scoreFloor
        val img = ArrayList<V3>(38); val wld = ArrayList<V3>(38); val conf = FloatArray(38)
        for (i in 0 until 33) {
            val l = frame.image[i]
            img += V3(l.p.x * aspect, -l.p.y, 0f)
            wld += Coordinates.world(frame.world[i].p)
            conf[i] = if (frame.observedJoints?.getOrNull(i) == false && l.confidence < floor) 0f else min(l.confidence, frame.world[i].confidence)
        }
        fun mid(a: Int, b: Int, at: Int) { img += (img[a] + img[b]) * .5f; wld += (wld[a] + wld[b]) * .5f; conf[at] = min(conf[a], conf[b]) }
        mid(23, 24, MID_HIP); mid(11, 12, MID_SHOULDER); mid(7, 8, MID_EAR)
        mid(17, 19, HAND_L); mid(18, 20, HAND_R)
        fun ok(vararg ids: Int) = ids.all { conf[it] >= floor }
        val depth = frame.hasWorldLandmarks

        /** Hybrid bone direction in camera space, or null when either end is not visible. */
        fun dir(a: Int, b: Int): V3? {
            if (!ok(a, b)) return null
            val v = img[b] - img[a]
            if (!depth) return if (v.length() < 1e-4f) null else v.unit()
            val w = wld[b] - wld[a]
            if (w.length() < 1e-4f) return if (v.length() < 1e-4f) null else v.unit()
            val wu = w.unit()
            if (v.length() < .012f) return wu
            val u = v.unit(); val s = sqrt((1 - wu.z * wu.z).coerceAtLeast(0f))
            return V3(u.x * s, u.y * s, wu.z)
        }

        // Gravity-aligned world basis expressed in camera coordinates.
        val yW = upCamera.unit()
        val zW = V3(0f, 0f, 1f).orthoTo(yW).let { if (it.length() < .5f) V3(0f, -1f, 0f).orthoTo(yW) else it }
        val xW = yW.cross(zW).unit()
        fun toW(v: V3) = V3(v.dot(xW), v.dot(yW), v.dot(zW))
        val up = V3.UP
        val camZ = V3(xW.z, yW.z, zW.z)
        /** Mirror a world direction's depth component about the camera image plane. */
        fun flipDepth(d: V3) = d - camZ * (2 * d.dot(camZ))

        // ---- Torso -------------------------------------------------------------------------
        val shouldersVisible = ok(11, 12)
        val hipsVisible = ok(23, 24)
        val previous = last
        if (!shouldersVisible && previous == null) return null
        val tracked = mutableSetOf<Chain>()
        var trunkUp: V3; var chestRight: V3; var pelvisRight: V3
        if (shouldersVisible) {
            tracked += Chain.TORSO
            trunkUp = (if (hipsVisible) dir(MID_HIP, MID_SHOULDER)?.let(::toW) else null) ?: up
            // Without depth, a side-on torso makes the shoulder line degenerate: keep the previous heading.
            val narrow = !depth && hipsVisible && (img[12] - img[11]).length() < .25f * (img[MID_SHOULDER] - img[MID_HIP]).length()
            val sr = (if (narrow) null else dir(11, 12)?.let(::toW)) ?: previous?.chestRight ?: V3(-1f, 0f, 0f)
            chestRight = sr.orthoTo(trunkUp)
            val hr = if (hipsVisible) dir(23, 24)?.let(::toW) else null
            pelvisRight = (hr ?: chestRight).orthoTo(trunkUp)
            // Limit shoulder/pelvis twist to an anatomical range (about 60 degrees).
            val twist = angleBetween(pelvisRight, chestRight)
            if (twist > 60f) pelvisRight = rotateToward(chestRight, pelvisRight, 60f, trunkUp)
        } else {
            trunkUp = previous!!.trunkUp; chestRight = previous.chestRight; pelvisRight = previous.pelvisRight
        }
        val chest = Basis.fromYZ(trunkUp, trunkUp.cross(chestRight))
        val pelvisB = Basis.fromYZ(trunkUp, trunkUp.cross(pelvisRight))
        val cF = chest.z; val pF = pelvisB.z

        // ---- Head --------------------------------------------------------------------------
        var head = chest
        if (ok(7, 8) || ok(0, 7) || ok(0, 8)) {
            val earRight = dir(7, 8)?.let(::toW)?.orthoTo(trunkUp) ?: chestRight
            val faceFwd = when { ok(0, 7, 8) -> dir(MID_EAR, 0); ok(0, 7) -> dir(7, 0); else -> dir(8, 0) }?.let(::toW)
            val fwd = (faceFwd ?: cF).orthoTo(earRight)
            val hu0 = earRight.cross(fwd).unit()
            // The nose sits slightly below the ear line: pitch the face up ~10 degrees.
            val hf = (fwd * cos(.17f) + hu0 * sin(.17f)).unit()
            var hb = Basis.fromYZ(earRight.cross(hf), hf)
            if (hb.y.dot(trunkUp) < .2f) hb = chest // implausible neck flexion; keep upright
            head = hb
            tracked += Chain.HEAD
        }

        // ---- Limb directions ---------------------------------------------------------------
        val now = frame.capturedMs
        fun limb(key: String, a: Int, b: Int, parent: Basis, rest: V3, constraint: ((V3) -> V3)? = null): V3 {
            var d = dir(a, b)?.let(::toW)
            if (d != null && constraint != null) d = constraint(d)
            if (d != null) {
                lastLocal[key] = parent.toLocal(d); lastSeen[key] = now
                return d
            }
            val local = lastLocal[key] ?: return parent.toWorld(rest).unit()
            val age = now - (lastSeen[key] ?: now)
            val relax = ((age - HOLD_MS) / 800f).coerceIn(0f, 1f)
            return parent.toWorld(local.lerp(rest, relax)).unit()
        }

        val sides = Side.entries.associateWith { side ->
            val o = if (side == Side.LEFT) 0 else 1
            val sign = if (side == Side.LEFT) -1f else 1f
            val sh = 11 + o; val el = 13 + o; val wr = 15 + o; val hp = 23 + o; val kn = 25 + o; val an = 27 + o; val he = 29 + o; val to = 31 + o
            // Local rest directions in (X=subject left, Y=up, Z=forward) of the parent frame.
            val armRest = V3(-sign * .12f, -1f, .05f).unit()
            val upperArm = limb("ua$o", sh, el, chest, armRest)
            val foreArm = limb("fa$o", el, wr, chest, V3(-sign * .05f, -1f, .25f).unit()) { d -> resolveHinge(upperArm, d, anteriorOf(upperArm, chest), 10f, ::flipDepth) }
            val hand = limb("ha$o", wr, if (side == Side.LEFT) HAND_L else HAND_R, chest, V3(0f, -1f, .2f).unit())
            val thigh = limb("th$o", hp, kn, pelvisB, V3(-sign * .04f, -1f, 0f).unit())
            val shank = limb("sh$o", kn, an, pelvisB, V3(-sign * .02f, -1f, 0f).unit()) { d -> resolveHinge(thigh, d, -anteriorOf(thigh, pelvisB), 8f, ::flipDepth) }
            val footRaw = limb("fo$o", he, to, pelvisB, V3(0f, -.05f, 1f).unit())
            val armTracked = ok(sh, el) && (ok(wr) || lastSeen["fa$o"]?.let { now - it < 300 } == true)
            val legTracked = ok(hp, kn, an)
            if (armTracked) tracked += Chain.arm(side)
            if (legTracked && hipsVisible) tracked += Chain.leg(side)
            Limb(upperArm, foreArm, hand, thigh, shank, footRaw)
        }

        // ---- Forward kinematics (pelvis centre at origin) -----------------------------------
        val pelvis = V3.ZERO
        val neckBase = trunkUp * BodyDims.SPINE
        val neckTop = neckBase + trunkUp * BodyDims.NECK
        val headCenter = neckTop + head.y * BodyDims.HEAD_CENTER
        val frames = mutableMapOf<Seg, Basis>()
        val origins = mutableMapOf<Seg, V3>()
        frames[Seg.PELVIS] = pelvisB; origins[Seg.PELVIS] = pelvis
        frames[Seg.CHEST] = chest; origins[Seg.CHEST] = pelvis
        frames[Seg.NECK] = chest; origins[Seg.NECK] = neckBase
        frames[Seg.HEAD] = head; origins[Seg.HEAD] = neckTop
        val points = mutableMapOf<Side, SidePoints>()
        for (side in Side.entries) {
            val l = sides.getValue(side)
            val sign = if (side == Side.LEFT) -1f else 1f
            val shoulder = neckBase + chestRight * (sign * BodyDims.SHOULDER_HALF) - trunkUp * BodyDims.SHOULDER_DROP
            val elbow = shoulder + l.upperArm * BodyDims.UPPER_ARM
            val wrist = elbow + l.forearm * BodyDims.FOREARM
            val handTip = wrist + l.hand * BodyDims.HAND
            val hip = pelvis + pelvisRight * (sign * BodyDims.HIP_HALF)
            val knee = hip + l.thigh * BodyDims.THIGH
            val ankle = knee + l.shank * BodyDims.SHANK
            // Anterior (Z) axes: once a hinge is bent, the bend shows which way the limb faces;
            // otherwise the parent's forward axis is carried along with the limb.
            val elbowBend = angleBetween(l.upperArm, l.forearm)
            val kneeBend = angleBetween(l.thigh, l.shank)
            val uaB = Basis.fromYZ(l.upperArm, blendAnterior(l.upperArm, l.forearm, elbowBend, anteriorOf(l.upperArm, chest), 1f))
            val faB = Basis.fromYZ(l.forearm, blendAnterior(l.forearm, -l.upperArm, elbowBend, uaB.z, 1f))
            val haB = Basis.fromYZ(l.hand, faB.z)
            val thB = Basis.fromYZ(l.thigh, blendAnterior(l.thigh, l.shank, kneeBend, anteriorOf(l.thigh, pelvisB), -1f))
            val shB = Basis.fromYZ(l.shank, blendAnterior(l.shank, l.thigh, kneeBend, thB.z, 1f))
            // Foot: Y along the sole (heel -> toe), Z = top of the foot. Ankle range limited to 40..125 degrees from the shank.
            val footFwd = clampAngleFrom(l.shank, l.foot, 40f, 125f, shB.x)
            val ftB = Basis.fromYZ(footFwd, -l.shank)
            val heel = ankle - ftB.z * BodyDims.ANKLE_HEIGHT - ftB.y * BodyDims.HEEL_BACK
            val toe = ankle - ftB.z * BodyDims.ANKLE_HEIGHT + ftB.y * BodyDims.TOE_FORWARD
            frames[Seg.upperArm(side)] = uaB; origins[Seg.upperArm(side)] = shoulder
            frames[Seg.forearm(side)] = faB; origins[Seg.forearm(side)] = elbow
            frames[Seg.hand(side)] = haB; origins[Seg.hand(side)] = wrist
            frames[Seg.thigh(side)] = thB; origins[Seg.thigh(side)] = hip
            frames[Seg.shank(side)] = shB; origins[Seg.shank(side)] = knee
            frames[Seg.foot(side)] = ftB; origins[Seg.foot(side)] = ankle
            points[side] = SidePoints(shoulder, elbow, wrist, handTip, hip, knee, ankle, heel, toe)
        }

        // ---- Ground the body: the lowest contact surface rests on the floor (y = 0) ---------
        val contactHeights = mutableListOf<Float>()
        for (p in points.values) {
            contactHeights += p.heel.y; contactHeights += p.toe.y
            contactHeights += p.knee.y - .05f; contactHeights += p.elbow.y - .04f
            contactHeights += (p.wrist.y + p.handTip.y) * .5f - .03f
        }
        contactHeights += pelvis.y - .11f; contactHeights += neckBase.y - .10f
        val target = -contactHeights.min()
        val dtS = previous?.let { ((frame.capturedMs - it.time) / 1000f).coerceIn(.001f, .2f) } ?: 1f
        val offset = floorOffset?.let { it + (target - it) * (1 - exp(-dtS / .06f)) } ?: target
        floorOffset = offset
        val lift = V3(0f, offset, 0f)
        fun sp(p: SidePoints) = SidePoints(p.shoulder + lift, p.elbow + lift, p.wrist + lift, p.handTip + lift,
            p.hip + lift, p.knee + lift, p.ankle + lift, p.heel + lift, p.toe + lift)
        val state = BodyState(frame.capturedMs, frames, origins.mapValues { it.value + lift },
            pelvis + lift, neckBase + lift, headCenter + lift,
            sp(points.getValue(Side.LEFT)), sp(points.getValue(Side.RIGHT)), tracked,
            cameraX = V3(xW.x, yW.x, zW.x), cameraY = V3(xW.y, yW.y, zW.y), cameraZ = V3(xW.z, yW.z, zW.z),
            hasDepth = depth)
        last = state
        return state
    }

    private data class Limb(val upperArm: V3, val forearm: V3, val hand: V3, val thigh: V3, val shank: V3, val foot: V3)

    /** The parent's forward axis carried along by the minimal rotation from hanging (-Y) to [d]. */
    private fun anteriorOf(d: V3, parent: Basis): V3 {
        val down = -parent.y
        val axis = down.cross(d)
        val s = axis.length(); val c = down.dot(d)
        if (s < 1e-5f) return if (c > 0) parent.z else -parent.z
        return rotate(parent.z, axis, atan2(s, c)).orthoTo(d)
    }

    /**
     * Anterior direction of a proximal segment: once the hinge is bent, the distal segment shows
     * which way the joint flexes ([sign] = 1 when flexion is anterior, -1 when posterior, as for the knee).
     */
    private fun blendAnterior(seg: V3, distal: V3, bend: Float, fallback: V3, sign: Float): V3 {
        val fromBend = (distal - seg * distal.dot(seg))
        val w = ((bend - 12f) / 18f).coerceIn(0f, 1f)
        if (w <= 0f || fromBend.length() < 1e-4f) return fallback.orthoTo(seg)
        val b = fromBend.unit() * sign
        val f = fallback.orthoTo(seg)
        // Avoid a sudden flip if the measured bend disagrees with the fallback hemisphere.
        val blended = if (b.dot(f) < -.2f) f else (b * w + f * (1 - w))
        return blended.orthoTo(seg)
    }

    /**
     * Monocular depth is ambiguous: a forearm/shank can be estimated in front of or behind its
     * parent. If the measured direction hyperextends the hinge, try the mirrored depth instead.
     */
    private fun resolveHinge(parent: V3, d: V3, flexDir: V3, maxHyper: Float, flip: (V3) -> V3): V3 {
        fun hyper(v: V3): Float {
            val perp = v - parent * v.dot(parent)
            if (perp.length() < 1e-4f) return 0f
            val along = -perp.unit().dot(flexDir) // >0 when bending opposite to flexion
            return if (along > 0) angleBetween(parent, v) * along else 0f
        }
        val flipped = flip(d).unit()
        return if (hyper(d) > maxHyper && hyper(flipped) < hyper(d)) flipped else d
    }

    /** Keep [v] within [minDeg]..[maxDeg] of [ref], rotating in the plane of the two (or about [axisHint]). */
    private fun clampAngleFrom(ref: V3, v: V3, minDeg: Float, maxDeg: Float, axisHint: V3): V3 {
        val a = angleBetween(ref, v)
        if (a in minDeg..maxDeg) return v.unit()
        var axis = ref.cross(v)
        if (axis.length() < 1e-5f) axis = axisHint
        return rotate(ref.unit(), axis, Math.toRadians(a.coerceIn(minDeg, maxDeg).toDouble()).toFloat()).unit()
    }
}

/** Rotate [from] toward [to] by at most [maxDegrees] about [axisHint]-compatible plane. */
internal fun rotateToward(from: V3, to: V3, maxDegrees: Float, axisHint: V3): V3 {
    val a = angleBetween(from, to)
    if (a <= maxDegrees) return to
    var axis = from.cross(to)
    if (axis.length() < 1e-5f) axis = axisHint
    return rotate(from, axis, Math.toRadians(maxDegrees.toDouble()).toFloat())
}
