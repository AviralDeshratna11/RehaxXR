package com.creatofit.motion.core

import kotlin.math.*

data class MuscleActivity(
    val muscle: Muscle, val side: Side,
    /** 0..1 estimated relative effort. */
    val level: Float,
    val role: MuscleRole,
    val contraction: Contraction,
    /** Joint action this muscle produces or resists, e.g. "knee extension". */
    val action: String,
    /** The motion being resisted (for eccentric/isometric work), e.g. "knee flexion". */
    val opposing: String,
) {
    val intensity get() = Intensity.of(level)
    val summary get() = when (contraction) {
        Contraction.CONCENTRIC -> "${contraction.verb} $action"
        else -> "${contraction.verb} $opposing"
    }
}

/** One row of the muscle list: both sides combined when they work together. */
data class MuscleEntry(val muscle: Muscle, val sides: Set<Side>, val level: Float, val role: MuscleRole,
    val contraction: Contraction, val summary: String, val action: String) {
    val sideLabel get() = when { sides.size == 2 -> "Both sides"; sides.first() == Side.LEFT -> "Left"; else -> "Right" }
    val intensity get() = Intensity.of(level)
}

data class Movement(val title: String, val phase: String, val support: String)

data class MuscleAnalysis(
    val time: Long,
    val activities: List<MuscleActivity>,
    val movement: Movement,
    val angles: JointAngles?,
    /** Highlight level per avatar region node (e.g. "quads_l"). */
    val regions: Map<String, Float>,
) {
    val entries: List<MuscleEntry> by lazy {
        activities.groupBy { it.muscle }.map { (m, list) ->
            val top = list.maxBy { it.level }
            val both = list.size == 2 && list.minOf { it.level } > top.level * .55f
            MuscleEntry(m, if (both) list.map { it.side }.toSet() else setOf(top.side), top.level, top.role, top.contraction, top.summary, top.action)
        }.sortedWith(compareBy<MuscleEntry> { it.role.ordinal / 2 }.thenByDescending { it.level })
    }
    companion object {
        val EMPTY = MuscleAnalysis(0, emptyList(), Movement("Waiting for you", "Step into view", ""), null, emptyMap())
    }
}

/**
 * Converts joint moments into muscle involvement.
 *
 * The muscle group that must produce each net joint moment is active (e.g. a knee-extension moment
 * requires the quadriceps). Whether it shortens, lengthens or holds follows from the sign of joint
 * power (moment x angular velocity). Fast unloaded movements additionally recruit the agonist of the
 * direction of motion. Levels are smoothed (fast attack, slower release) and gated with hysteresis.
 */
class MuscleEngine {
    private data class Contribution(val muscle: Muscle, val weight: Float, val role: MuscleRole)
    private data class Rule(val positive: List<Contribution>, val negative: List<Contribution>, val posAction: String, val negAction: String)
    private data class Candidate(val level: Float, val role: MuscleRole, val contraction: Contraction, val action: String, val opposing: String)
    private class Track(var level: Float = 0f, var shown: Boolean = false, var contraction: Contraction = Contraction.ISOMETRIC,
        var pending: Contraction = Contraction.ISOMETRIC, var pendingSince: Long = 0, var role: MuscleRole = MuscleRole.SYNERGIST,
        var action: String = "", var opposing: String = "")

    private fun c(m: Muscle, w: Float, r: MuscleRole = if (w >= .8f) MuscleRole.PRIME else MuscleRole.SYNERGIST) = Contribution(m, w, r)

    private val rules: Map<Pair<JointId, Comp>, Rule> = mapOf(
        (JointId.LUMBAR to Comp.FLEX) to Rule(
            listOf(c(Muscle.RECTUS_ABDOMINIS, 1f), c(Muscle.OBLIQUES, .65f)),
            listOf(c(Muscle.ERECTOR_SPINAE, 1f)), "trunk flexion", "trunk extension"),
        (JointId.SHOULDER to Comp.FLEX) to Rule(
            listOf(c(Muscle.ANTERIOR_DELTOID, 1f), c(Muscle.PECTORALIS_MAJOR, .5f), c(Muscle.BICEPS_BRACHII, .25f)),
            listOf(c(Muscle.LATISSIMUS_DORSI, 1f), c(Muscle.POSTERIOR_DELTOID, .6f), c(Muscle.TRICEPS_BRACHII, .35f)),
            "shoulder flexion", "shoulder extension"),
        (JointId.SHOULDER to Comp.ABD) to Rule(
            listOf(c(Muscle.LATERAL_DELTOID, 1f), c(Muscle.ANTERIOR_DELTOID, .35f)),
            listOf(c(Muscle.LATISSIMUS_DORSI, 1f), c(Muscle.PECTORALIS_MAJOR, .7f)),
            "shoulder abduction", "shoulder adduction"),
        (JointId.SHOULDER to Comp.HADD) to Rule(
            listOf(c(Muscle.PECTORALIS_MAJOR, 1f), c(Muscle.ANTERIOR_DELTOID, .7f)),
            listOf(c(Muscle.POSTERIOR_DELTOID, 1f), c(Muscle.RHOMBOIDS, .8f)),
            "horizontal adduction", "horizontal abduction"),
        (JointId.ELBOW to Comp.FLEX) to Rule(
            listOf(c(Muscle.BICEPS_BRACHII, 1f), c(Muscle.BRACHIALIS, .9f), c(Muscle.BRACHIORADIALIS, .6f)),
            listOf(c(Muscle.TRICEPS_BRACHII, 1f)), "elbow flexion", "elbow extension"),
        (JointId.HIP to Comp.FLEX) to Rule(
            listOf(c(Muscle.ILIOPSOAS, 1f), c(Muscle.QUADRICEPS, .45f)),
            listOf(c(Muscle.GLUTEUS_MAXIMUS, 1f), c(Muscle.HAMSTRINGS, .7f), c(Muscle.ADDUCTORS, .35f)),
            "hip flexion", "hip extension"),
        (JointId.HIP to Comp.ABD) to Rule(
            listOf(c(Muscle.GLUTEUS_MEDIUS, 1f)),
            listOf(c(Muscle.ADDUCTORS, 1f)), "hip abduction", "hip adduction"),
        (JointId.KNEE to Comp.FLEX) to Rule(
            listOf(c(Muscle.HAMSTRINGS, 1f), c(Muscle.GASTROCNEMIUS, .4f)),
            listOf(c(Muscle.QUADRICEPS, 1f)), "knee flexion", "knee extension"),
        (JointId.ANKLE to Comp.FLEX) to Rule(
            listOf(c(Muscle.GASTROCNEMIUS, 1f), c(Muscle.SOLEUS, 1f)),
            listOf(c(Muscle.TIBIALIS_ANTERIOR, 1f)), "plantarflexion (pointing the foot)", "dorsiflexion (lifting the toes)"),
    )

    private val tracks = mutableMapOf<Pair<Muscle, Side>, Track>()
    private var lastTime = -1L
    private val posture = PostureClassifier()

    fun reset() { tracks.clear(); lastTime = -1; posture.reset() }

    fun update(body: BodyState, bio: BiomechResult): MuscleAnalysis {
        val dt = if (lastTime < 0) .033f else ((body.time - lastTime) / 1000f).coerceIn(.001f, .3f)
        lastTime = body.time
        val candidates = mutableMapOf<Pair<Muscle, Side>, Candidate>()
        fun offer(m: Muscle, side: Side, cand: Candidate) {
            val key = m to side
            val old = candidates[key]
            if (old == null || cand.level > old.level) candidates[key] = cand
        }
        fun sidesFor(load: JointLoad, m: Muscle, positive: Boolean): List<Side> = when {
            load.side != null -> listOf(load.side)
            load.comp == Comp.LAT -> listOf(if (positive) Side.RIGHT else Side.LEFT)
            load.comp == Comp.ROT -> listOf(Side.LEFT, Side.RIGHT)
            else -> listOf(Side.LEFT, Side.RIGHT)
        }

        for (load in bio.loads) {
            if (load.comp == Comp.LAT || load.comp == Comp.ROT) {
                // Side-bend: the obliques and quadratus lumborum of the bending side; rotation: both obliques.
                val positive = load.moment > 0
                val lvl = load.level.coerceAtMost(1.2f)
                val actionName = if (load.comp == Comp.LAT) "side bend to the ${if (positive) "right" else "left"}" else "trunk rotation"
                val opposite = if (load.comp == Comp.LAT) "side bend to the ${if (positive) "left" else "right"}" else "trunk rotation"
                val con = contraction(load)
                for (s in sidesFor(load, Muscle.OBLIQUES, positive)) {
                    offer(Muscle.OBLIQUES, s, Candidate(lvl, MuscleRole.PRIME, con, actionName, opposite))
                    if (load.comp == Comp.LAT) {
                        offer(Muscle.QUADRATUS_LUMBORUM, s, Candidate(lvl * .6f, MuscleRole.SYNERGIST, con, actionName, opposite))
                        offer(Muscle.ERECTOR_SPINAE, s, Candidate(lvl * .35f, MuscleRole.SYNERGIST, con, actionName, opposite))
                    }
                }
                continue
            }
            val rule = rules[load.joint to load.comp] ?: continue
            val positive = load.moment > 0
            val group = if (positive) rule.positive else rule.negative
            val action = if (positive) rule.posAction else rule.negAction
            val opposing = if (positive) rule.negAction else rule.posAction
            val lvl = load.level.coerceAtMost(1.2f)
            val con = contraction(load)
            for (contribution in group) for (s in sidesFor(load, contribution.muscle, positive))
                offer(contribution.muscle, s, Candidate(lvl * contribution.weight, contribution.role, con, action, opposing))
            // Fast motion with little gravitational load: the agonist of the motion accelerates the limb.
            val speed = abs(load.omega)
            if (speed > 1.2f && (load.level < .15f || (load.moment > 0) == (load.omega > 0))) {
                val motionPositive = load.omega > 0
                val drive = .35f * ((speed - 1.2f) / 2.5f).coerceIn(0f, 1f)
                val g = if (motionPositive) rule.positive else rule.negative
                val a = if (motionPositive) rule.posAction else rule.negAction
                val o = if (motionPositive) rule.negAction else rule.posAction
                for (contribution in g) for (s in sidesFor(load, contribution.muscle, motionPositive))
                    offer(contribution.muscle, s, Candidate(drive * contribution.weight, contribution.role, Contraction.CONCENTRIC, a, o))
            }
        }

        // ---- Stabilisers ----------------------------------------------------------------------
        for (side in Side.entries) {
            val shoulder = bio.loads.filter { it.joint == JointId.SHOULDER && it.side == side }.maxOfOrNull { it.level } ?: 0f
            if (shoulder > .2f) offer(Muscle.ROTATOR_CUFF, side, Candidate(.55f * shoulder.coerceAtMost(1.2f), MuscleRole.STABILIZER,
                Contraction.ISOMETRIC, "joint centring", "the humeral head slipping in the socket"))
            val weightBearing = bio.contacts.filter { (it.point == ContactPoint.HAND || it.point == ContactPoint.ELBOW) && it.side == side }.sumOf { it.share.toDouble() }.toFloat()
            if (weightBearing > .08f) offer(Muscle.SERRATUS_ANTERIOR, side, Candidate((weightBearing * 2.2f).coerceIn(.3f, 1f), MuscleRole.STABILIZER,
                Contraction.ISOMETRIC, "scapular protraction", "the shoulder blade winging off the ribs"))
            // Closed chain: a bent elbow carrying body weight through the hand must be braced by the triceps.
            val handLoad = bio.contacts.filter { it.point == ContactPoint.HAND && it.side == side }.sumOf { it.share.toDouble() }.toFloat()
            val bend = bio.angles.elbowFlexion[side] ?: 0f
            if (handLoad > .08f && bend > 25f && body.isTracked(Chain.arm(side))) {
                val elbowLoad = bio.load(JointId.ELBOW, side, Comp.FLEX)
                val l = (handLoad * 2.5f).coerceAtMost(1f) * ((bend - 25f) / 65f).coerceIn(0f, 1f) * .7f
                offer(Muscle.TRICEPS_BRACHII, side, Candidate(l, MuscleRole.SYNERGIST,
                    elbowLoad?.let { if (abs(it.omega) < .45f) Contraction.ISOMETRIC else if (it.omega < 0) Contraction.CONCENTRIC else Contraction.ECCENTRIC } ?: Contraction.ISOMETRIC,
                    "elbow extension", "elbow flexion"))
            }
            val elevation = bio.angles.shoulderElevation[side] ?: 0f
            val raising = bio.loads.filter { it.joint == JointId.SHOULDER && it.side == side && (it.comp == Comp.FLEX || it.comp == Comp.ABD) && it.moment > 0 }.maxOfOrNull { it.level } ?: 0f
            // Above ~90 degrees the shoulder blade must rotate upward and carry the arm's weight
            // (upper trapezius + serratus anterior), even when the arm is balanced vertically overhead.
            val overhead = if (elevation > 110f && body.isTracked(Chain.arm(side)) && weightBearing < .08f) .35f * ((elevation - 110f) / 40f).coerceIn(0f, 1f) else 0f
            if (elevation > 75f && (raising > .15f || overhead > 0f)) {
                val l = max(raising.coerceAtMost(1f) * ((elevation - 75f) / 45f).coerceIn(.3f, 1f), overhead + raising * .3f)
                val role = if (raising > .15f) MuscleRole.SYNERGIST else MuscleRole.STABILIZER
                offer(Muscle.UPPER_TRAPEZIUS, side, Candidate(l * .8f, role, Contraction.ISOMETRIC, "scapular upward rotation", "the shoulder blade dropping"))
                offer(Muscle.SERRATUS_ANTERIOR, side, Candidate(l * .7f, role, Contraction.ISOMETRIC, "scapular upward rotation", "the shoulder blade dropping"))
                if (overhead > 0f) offer(Muscle.ROTATOR_CUFF, side, Candidate(overhead, MuscleRole.STABILIZER, Contraction.ISOMETRIC, "joint centring", "the humeral head slipping in the socket"))
            }
        }
        val core = bio.load(JointId.LUMBAR, null, Comp.FLEX)
        val bridging = bio.touching(ContactPoint.HAND) || bio.touching(ContactPoint.ELBOW)
        if (core != null && (core.level > .25f || bridging && bio.touching(ContactPoint.TOE))) {
            val l = max(core.level, if (bridging) .4f else 0f).coerceAtMost(1f) * .6f
            for (s in Side.entries) offer(Muscle.TRANSVERSUS_ABDOMINIS, s, Candidate(l, MuscleRole.STABILIZER, Contraction.ISOMETRIC, "core bracing", "the spine buckling"))
        }

        // ---- Temporal smoothing & hysteresis ---------------------------------------------------
        val activities = mutableListOf<MuscleActivity>()
        val keys = candidates.keys + tracks.keys
        for (key in keys) {
            val cand = candidates[key]
            val t = tracks.getOrPut(key) { Track() }
            val target = (cand?.level ?: 0f).coerceIn(0f, 1f)
            val tau = if (target > t.level) .08f else .30f
            t.level += (target - t.level) * (1 - exp(-dt / tau))
            if (cand != null) {
                t.role = cand.role; t.action = cand.action; t.opposing = cand.opposing
                if (cand.contraction != t.pending) { t.pending = cand.contraction; t.pendingSince = body.time }
                if (t.pending != t.contraction && body.time - t.pendingSince >= 150) t.contraction = t.pending
                if (!t.shown) t.contraction = cand.contraction
            }
            t.shown = if (t.shown) t.level >= .09f else t.level >= .14f
            if (t.shown) activities += MuscleActivity(key.first, key.second, t.level, t.role, t.contraction, t.action, t.opposing)
        }
        val regions = mutableMapOf<String, Float>()
        for (a in activities) {
            val r = a.muscle.region ?: continue
            val node = r + if (a.side == Side.LEFT) "_l" else "_r"
            val weight = if (a.muscle.deep) .8f else 1f
            regions[node] = max(regions[node] ?: 0f, a.level * weight)
        }
        activities.sortByDescending { it.level }
        return MuscleAnalysis(body.time, activities, posture.classify(body, bio), bio.angles, regions)
    }

    private fun contraction(load: JointLoad): Contraction {
        if (abs(load.omega) < .45f) return Contraction.ISOMETRIC
        return if ((load.moment > 0) == (load.omega > 0)) Contraction.CONCENTRIC else Contraction.ECCENTRIC
    }
}

/** Names the overall body position and its phase from posture, floor contacts and motion. */
class PostureClassifier {
    private var title = ""
    private var candidate = ""
    private var since = 0L
    fun reset() { title = ""; candidate = ""; since = 0 }

    fun classify(b: BodyState, bio: BiomechResult): Movement {
        val a = bio.angles
        val legs = b.isTracked(Chain.LEG_L) && b.isTracked(Chain.LEG_R)
        val armsUp = Side.entries.count { (a.shoulderElevation[it] ?: 0f) > 140f && b.isTracked(Chain.arm(it)) }
        val armRaised = Side.entries.filter { (a.shoulderElevation[it] ?: 0f) > 60f && b.isTracked(Chain.arm(it)) }
        val curls = Side.entries.filter { (a.elbowFlexion[it] ?: 0f) > 70f && (a.shoulderElevation[it] ?: 0f) < 45f && b.isTracked(Chain.arm(it)) }
        val hands = bio.touching(ContactPoint.HAND); val elbows = bio.touching(ContactPoint.ELBOW)
        val feet = Side.entries.filter { bio.touching(ContactPoint.HEEL, it) || bio.touching(ContactPoint.TOE, it) }
        val knees = bio.touching(ContactPoint.KNEE)
        val hipsDown = bio.touching(ContactPoint.PELVIS); val backDown = bio.touching(ContactPoint.BACK)
        val kneeL = a.kneeFlexion[Side.LEFT] ?: 0f; val kneeR = a.kneeFlexion[Side.RIGHT] ?: 0f
        val maxKnee = max(kneeL, kneeR)
        val elbow = Side.entries.maxOf { a.elbowFlexion[it] ?: 0f }
        val stance = (b.left.ankle - b.right.ankle).let { d -> abs(d.dot(b.pelvisForward.let { f -> (f - V3.UP * f.dot(V3.UP)).unit() })) }
        val heelsUp = legs && Side.entries.all { bio.touching(ContactPoint.TOE, it) && !bio.touching(ContactPoint.HEEL, it) }

        val raw = when {
            a.trunkLean > 60f -> when {
                backDown && !hipsDown && feet.isNotEmpty() -> "Glute bridge"
                hipsDown || backDown -> if (b.trunkUp.dot(V3.UP) > .35f) "Sit-up / crunch" else "Lying down"
                elbows && feet.isNotEmpty() -> "Forearm plank"
                elbows && knees -> "Kneeling forearm plank"
                hands && knees -> if (elbow > 45f) "Kneeling push-up" else "Kneeling plank"
                hands && feet.isNotEmpty() -> if (elbow > 40f) "Push-up" else "High plank"
                !legs && hands -> "Plank / push-up position"
                else -> "Bent-over position"
            }
            knees && legs -> "Kneeling"
            legs && feet.size == 1 -> if (Side.entries.any { (a.hipFlexion[it] ?: 0f) > 60f }) "Knee raise" else "Single-leg stance"
            legs && stance > .38f && maxKnee > 35f -> "Lunge"
            legs && maxKnee > 45f -> "Squat"
            a.trunkLean > 30f && maxKnee < 40f -> "Hip hinge"
            abs(a.trunkSideBend) > 15f -> "Side bend"
            heelsUp -> "Calf raise"
            armsUp == 2 -> "Overhead reach"
            armRaised.size == 2 -> "Arm raise (both arms)"
            armRaised.size == 1 -> "${armRaised[0].label} arm raise"
            curls.size == 2 -> "Arm curl (both arms)"
            curls.size == 1 -> "${curls[0].label} arm curl"
            !legs && b.isTracked(Chain.TORSO) -> "Upper body"
            else -> "Standing"
        }
        if (raw != candidate) { candidate = raw; since = b.time }
        if (title.isEmpty() || b.time - since >= 250) title = candidate

        val vy = bio.comVelocity.y
        val phase = when {
            vy < -.10f -> "Lowering"
            vy > .10f -> "Rising"
            else -> {
                val armSpeed = bio.loads.filter { it.joint == JointId.SHOULDER || it.joint == JointId.ELBOW }.maxOfOrNull { abs(it.omega) } ?: 0f
                if (armSpeed > 1.0f) "Moving" else "Holding"
            }
        }
        val support = bio.contacts.groupBy { group(it.point) }.entries.sortedByDescending { e -> e.value.sumOf { it.share.toDouble() } }
            .joinToString(" · ") { (g, list) ->
                val share = (list.sumOf { it.share.toDouble() } * 100).roundToInt()
                val sides = list.mapNotNull { it.side }.distinct()
                val name = if (sides.size == 1) "${sides[0].label} ${g.second}" else g.first
                "$name $share%"
            }
        return Movement(title, phase, support)
    }

    /** Plural / singular display names for a support region. */
    private fun group(p: ContactPoint) = when (p) {
        ContactPoint.HEEL, ContactPoint.TOE -> "Feet" to "foot"
        ContactPoint.HAND -> "Hands" to "hand"
        ContactPoint.ELBOW -> "Forearms" to "forearm"
        ContactPoint.KNEE -> "Knees" to "knee"
        ContactPoint.PELVIS -> "Hips" to "hip"
        ContactPoint.BACK -> "Upper back" to "upper back"
    }
}
