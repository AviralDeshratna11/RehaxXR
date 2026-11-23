package com.creatofit.motion.core

import kotlin.math.*

enum class JointId(val label: String) { LUMBAR("Trunk"), SHOULDER("Shoulder"), ELBOW("Elbow"), HIP("Hip"), KNEE("Knee"), ANKLE("Ankle") }

/**
 * Moment components. Positive values mean: FLEX = flexion (ankle: plantarflexion), ABD = abduction,
 * HADD = horizontal adduction, LAT = side-bend to the right, ROT = trunk rotation to the right.
 */
enum class Comp { FLEX, ABD, HADD, LAT, ROT }

data class JointLoad(
    val joint: JointId, val side: Side?, val comp: Comp,
    /** Net muscle moment, body-weight x metres (positive = see [Comp]). */
    val moment: Float,
    /** Moment relative to a demanding body-weight reference for this joint (0 = none, 1 = high). */
    val level: Float,
    /** Relative joint angular velocity about the same axis, rad/s (positive = toward the positive action). */
    val omega: Float,
)

enum class ContactPoint(val label: String) { HEEL("heel"), TOE("forefoot"), KNEE("knee"), HAND("hand"), ELBOW("forearm"), PELVIS("hips"), BACK("upper back") }
data class Contact(val point: ContactPoint, val side: Side?, val position: V3, val share: Float)

data class BiomechResult(
    val loads: List<JointLoad>,
    val contacts: List<Contact>,
    val centerOfMass: V3,
    val comVelocity: V3,
    val angles: JointAngles,
) {
    fun load(j: JointId, s: Side?, c: Comp) = loads.firstOrNull { it.joint == j && it.side == s && it.comp == c }
    fun touching(p: ContactPoint, s: Side? = null) = contacts.any { it.point == p && (s == null || it.side == s) }
}

data class JointAngles(
    val trunkLean: Float, val trunkSideBend: Float,
    val hipFlexion: Map<Side, Float>, val kneeFlexion: Map<Side, Float>, val ankle: Map<Side, Float>,
    val shoulderElevation: Map<Side, Float>, val elbowFlexion: Map<Side, Float>,
)

/**
 * Quasi-static inverse dynamics on the solved skeleton.
 *
 * For each joint, the net muscle moment balances the weight of every segment distal to it and the
 * ground reaction forces acting on that sub-chain: M = sum r x (m * up) - sum r_c x F_c.
 * Segment masses and centre-of-mass positions follow de Leva (1996). Body weight is distributed over
 * the floor contacts so that their combined support lies under the whole-body centre of mass.
 * External loads (dumbbells etc.) cannot be seen by the camera and are not included.
 */
class Biomechanics {
    private enum class Part(val mass: Float) {
        TRUNK(.4346f), HEAD(.0694f),
        UA_L(.0271f), FA_L(.0162f), HAND_L(.0061f), UA_R(.0271f), FA_R(.0162f), HAND_R(.0061f),
        TH_L(.1416f), SH_L(.0433f), FOOT_L(.0137f), TH_R(.1416f), SH_R(.0433f), FOOT_R(.0137f)
    }
    companion object {
        private val REFERENCE = mapOf(
            JointId.LUMBAR to .10f, JointId.SHOULDER to .03f, JointId.ELBOW to .012f,
            JointId.HIP to .07f, JointId.KNEE to .06f, JointId.ANKLE to .05f)
        private val LATERAL_REFERENCE = mapOf(JointId.LUMBAR to .06f, JointId.HIP to .075f, JointId.SHOULDER to .03f)
        const val CONTACT_GAP = .065f
        const val DEADBAND = .10f
    }

    private var prevFrames: Map<Seg, Basis>? = null
    private var prevTime = -1L
    private val omega = mutableMapOf<Seg, V3>()
    private var prevCom: V3? = null
    private var comVelocity = V3.ZERO

    fun reset() { prevFrames = null; prevTime = -1; omega.clear(); prevCom = null; comVelocity = V3.ZERO }

    fun analyze(b: BodyState): BiomechResult {
        val dt = if (prevTime < 0) 0f else ((b.time - prevTime) / 1000f)
        if (dt > .3f || dt < 0f) reset()
        // ---- Segment angular velocities (small-angle, filtered) --------------------------------
        val pf = prevFrames
        if (pf != null && dt > 0f) {
            val a = 1 - exp(-dt / .07f)
            for ((seg, cur) in b.frames) {
                val prev = pf[seg] ?: continue
                val w = (prev.x.cross(cur.x) + prev.y.cross(cur.y) + prev.z.cross(cur.z)) * (.5f / dt)
                val clamped = if (w.length() > 25f) w.unit() * 25f else w
                omega[seg] = (omega[seg] ?: V3.ZERO).lerp(clamped, a)
            }
        }
        prevFrames = b.frames; prevTime = b.time
        fun w(s: Seg) = omega[s] ?: V3.ZERO

        // ---- Segment centres of mass --------------------------------------------------------
        val com = mutableMapOf<Part, V3>()
        com[Part.TRUNK] = b.pelvis + (b.neckBase - b.pelvis) * .55f
        com[Part.HEAD] = b.headCenter
        for (side in Side.entries) {
            val p = b.side(side); val l = side == Side.LEFT
            com[if (l) Part.UA_L else Part.UA_R] = p.shoulder + (p.elbow - p.shoulder) * .577f
            com[if (l) Part.FA_L else Part.FA_R] = p.elbow + (p.wrist - p.elbow) * .457f
            com[if (l) Part.HAND_L else Part.HAND_R] = p.wrist + (p.handTip - p.wrist) * .40f
            com[if (l) Part.TH_L else Part.TH_R] = p.hip + (p.knee - p.hip) * .41f
            com[if (l) Part.SH_L else Part.SH_R] = p.knee + (p.ankle - p.knee) * .446f
            com[if (l) Part.FOOT_L else Part.FOOT_R] = p.heel + (p.toe - p.heel) * .44f
        }
        val bodyCom = Part.entries.fold(V3.ZERO) { acc, part -> acc + com.getValue(part) * part.mass }
        prevCom?.let { pc -> if (dt > 0f) comVelocity = comVelocity.lerp((bodyCom - pc) * (1f / dt), 1 - exp(-dt / .12f)) }
        prevCom = bodyCom

        // ---- Floor contacts -----------------------------------------------------------------
        data class Candidate(val point: ContactPoint, val side: Side?, val pos: V3, val height: Float)
        val candidates = mutableListOf<Candidate>()
        for (side in Side.entries) {
            val p = b.side(side)
            candidates += Candidate(ContactPoint.HEEL, side, p.heel, p.heel.y)
            candidates += Candidate(ContactPoint.TOE, side, p.toe, p.toe.y)
            if (b.isTracked(Chain.leg(side))) candidates += Candidate(ContactPoint.KNEE, side, p.knee, p.knee.y - .05f)
            if (b.isTracked(Chain.arm(side))) {
                val hand = (p.wrist + p.handTip) * .5f
                candidates += Candidate(ContactPoint.HAND, side, hand, hand.y - .03f)
                candidates += Candidate(ContactPoint.ELBOW, side, p.elbow, p.elbow.y - .04f)
            }
        }
        candidates += Candidate(ContactPoint.PELVIS, null, b.pelvis, b.pelvis.y - .11f)
        candidates += Candidate(ContactPoint.BACK, null, b.neckBase, b.neckBase.y - .10f)
        val floor = candidates.minOf { it.height }
        val touching = candidates.filter { it.height - floor < CONTACT_GAP }
        val shares = distribute(touching.map { it.pos }, bodyCom)
        val contacts = touching.mapIndexed { i, c -> Contact(c.point, c.side, c.pos, shares[i]) }

        // ---- Net joint moments ---------------------------------------------------------------
        val up = V3.UP
        fun moment(joint: V3, parts: List<Part>, points: List<Pair<ContactPoint, Side?>>): V3 {
            var m = V3.ZERO
            for (part in parts) m += (com.getValue(part) - joint).cross(up * part.mass)
            for (c in contacts) if (points.any { it.first == c.point && it.second == c.side })
                m -= (c.position - joint).cross(up * c.share)
            return m
        }
        val loads = mutableListOf<JointLoad>()
        fun add(j: JointId, s: Side?, c: Comp, m: V3, axis: V3, omegaRel: V3) {
            val a = axis.unit()
            val v = m.dot(a)
            val ref = if (c == Comp.FLEX || c == Comp.HADD) REFERENCE.getValue(j) else LATERAL_REFERENCE[j] ?: REFERENCE.getValue(j)
            // Small deadband: quiet standing and pose-estimation noise produce no muscle labels.
            val level = ((abs(v) / ref - DEADBAND) / (1 - DEADBAND)).coerceAtLeast(0f)
            loads += JointLoad(j, s, c, v, level, omegaRel.dot(a))
        }

        val chest = b.frame(Seg.CHEST); val pelvis = b.frame(Seg.PELVIS)
        val cR = -chest.x; val cU = chest.y; val cF = chest.z
        val pR = -pelvis.x; val pU = pelvis.y; val pF = pelvis.z
        if (b.isTracked(Chain.TORSO)) {
            val upper = listOf(Part.TRUNK, Part.HEAD, Part.UA_L, Part.FA_L, Part.HAND_L, Part.UA_R, Part.FA_R, Part.HAND_R)
            val pts = Side.entries.flatMap { listOf(ContactPoint.HAND to it, ContactPoint.ELBOW to it) } + (ContactPoint.BACK to null)
            val m = moment(b.pelvis, upper, pts)
            // With the hips resting on the floor (sit-ups, lying), trunk motion is measured against gravity.
            val wRel = if (contacts.any { it.point == ContactPoint.PELVIS }) w(Seg.CHEST) else w(Seg.CHEST) - w(Seg.PELVIS)
            add(JointId.LUMBAR, null, Comp.FLEX, m, -cR, wRel)
            add(JointId.LUMBAR, null, Comp.LAT, m, cF, wRel)
            add(JointId.LUMBAR, null, Comp.ROT, m, -cU, wRel)
        }
        for (side in Side.entries) {
            val p = b.side(side)
            val out = if (side == Side.RIGHT) cR else -cR
            val outP = if (side == Side.RIGHT) pR else -pR
            val l = side == Side.LEFT
            if (b.isTracked(Chain.arm(side))) {
                val ua = b.frame(Seg.upperArm(side))
                val arm = if (l) listOf(Part.UA_L, Part.FA_L, Part.HAND_L) else listOf(Part.UA_R, Part.FA_R, Part.HAND_R)
                val armPts = listOf(ContactPoint.HAND to side, ContactPoint.ELBOW to side)
                val ms = moment(p.shoulder, arm, armPts)
                val h = ua.y
                val perp = ms - h * ms.dot(h) // axial rotation of the humerus is not observable
                val wSh = w(Seg.upperArm(side)) - w(Seg.CHEST)
                add(JointId.SHOULDER, side, Comp.FLEX, perp, cR, wSh)
                add(JointId.SHOULDER, side, Comp.ABD, perp, (-cU).cross(out), wSh)
                add(JointId.SHOULDER, side, Comp.HADD, perp, out.cross(cF), wSh)
                val me = moment(p.elbow, arm.drop(1), armPts)
                add(JointId.ELBOW, side, Comp.FLEX, me, ua.x, w(Seg.forearm(side)) - w(Seg.upperArm(side)))
            }
            if (b.isTracked(Chain.leg(side))) {
                val th = b.frame(Seg.thigh(side)); val ft = b.frame(Seg.foot(side))
                val leg = if (l) listOf(Part.TH_L, Part.SH_L, Part.FOOT_L) else listOf(Part.TH_R, Part.SH_R, Part.FOOT_R)
                val legPts = listOf(ContactPoint.KNEE to side, ContactPoint.HEEL to side, ContactPoint.TOE to side)
                val mh = moment(p.hip, leg, legPts)
                val wHip = w(Seg.thigh(side)) - w(Seg.PELVIS)
                add(JointId.HIP, side, Comp.FLEX, mh, pR, wHip)
                add(JointId.HIP, side, Comp.ABD, mh, (-pU).cross(outP), wHip)
                val mk = moment(p.knee, leg.drop(1), legPts)
                add(JointId.KNEE, side, Comp.FLEX, mk, -th.x, w(Seg.shank(side)) - w(Seg.thigh(side)))
                val ma = moment(p.ankle, leg.drop(2), legPts)
                add(JointId.ANKLE, side, Comp.FLEX, ma, -ft.x, w(Seg.foot(side)) - w(Seg.shank(side)))
            }
        }
        return BiomechResult(loads, contacts, bodyCom, comVelocity, angles(b))
    }

    private fun angles(b: BodyState): JointAngles {
        val up = V3.UP
        val trunk = b.trunkUp
        val lean = angleBetween(trunk, up)
        val pr = b.pelvisRight
        val horizontalRight = (pr - up * pr.dot(up)).let { if (it.length() < .3f) pr else it.unit() }
        val side = Math.toDegrees(asin(trunk.dot(horizontalRight).coerceIn(-1f, 1f).toDouble())).toFloat()
        fun per(f: (Side) -> Float) = Side.entries.associateWith(f)
        return JointAngles(
            trunkLean = lean, trunkSideBend = side,
            hipFlexion = per { angleBetween(b.frame(Seg.thigh(it)).y, -trunk) },
            kneeFlexion = per { angleBetween(b.frame(Seg.thigh(it)).y, b.frame(Seg.shank(it)).y) },
            ankle = per { angleBetween(b.frame(Seg.shank(it)).y, b.frame(Seg.foot(it)).y) - 90f },
            shoulderElevation = per { angleBetween(b.frame(Seg.upperArm(it)).y, -trunk) },
            elbowFlexion = per { angleBetween(b.frame(Seg.upperArm(it)).y, b.frame(Seg.forearm(it)).y) },
        )
    }

    /**
     * Weights (summing to 1) for the contact points whose weighted horizontal position best matches the
     * centre of mass, regularised toward an even split. Projected gradient descent on the simplex.
     */
    internal fun distribute(points: List<V3>, com: V3): FloatArray {
        val n = points.size
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(1f)
        val px = FloatArray(n) { points[it].x - com.x }; val pz = FloatArray(n) { points[it].z - com.z }
        val lambda = .0015f
        var w = FloatArray(n) { 1f / n }
        val maxSq = (0 until n).maxOf { px[it] * px[it] + pz[it] * pz[it] }
        val step = .5f / (n * maxSq + lambda).coerceAtLeast(1e-4f)
        repeat(120) {
            var sx = 0f; var sz = 0f
            for (i in 0 until n) { sx += w[i] * px[i]; sz += w[i] * pz[i] }
            w = projectSimplex(FloatArray(n) { i -> w[i] - step * (2 * (px[i] * sx + pz[i] * sz) + 2 * lambda * (w[i] - 1f / n)) })
        }
        return w
    }

    private fun projectSimplex(v: FloatArray): FloatArray {
        val u = v.sortedDescending()
        var css = 0f; var theta = 0f
        for (i in u.indices) {
            css += u[i]
            val t = (css - 1f) / (i + 1)
            if (u[i] - t > 0) theta = t
        }
        return FloatArray(v.size) { max(v[it] - theta, 0f) }
    }
}
