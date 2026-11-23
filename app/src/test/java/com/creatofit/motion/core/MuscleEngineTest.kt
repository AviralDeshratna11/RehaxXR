package com.creatofit.motion.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MuscleEngineTest {
    private class Pipeline {
        val filter = PoseFilter(); val solver = BodySolver(); val bio = Biomechanics(); val engine = MuscleEngine()
        var body: BodyState? = null; var mech: BiomechResult? = null
        fun step(out: PoseSynth.Output): MuscleAnalysis {
            val f = filter.apply(out.frame)!!
            val b = solver.solve(f, out.upCamera)!!
            val m = bio.analyze(b)
            body = b; mech = m
            return engine.update(b, m)
        }
    }

    private fun hold(pose: SynthPose, tilt: Float = 0f, frames: Int = 40, hidden: Set<Int> = emptySet()): Pair<MuscleAnalysis, Pipeline> {
        val p = Pipeline(); var a = MuscleAnalysis.EMPTY
        for (i in 0 until frames) a = p.step(PoseSynth.frame(pose, 1000L + i * 33L, cameraTilt = tilt, hidden = hidden))
        return a to p
    }
    private fun MuscleAnalysis.level(m: Muscle) = activities.filter { it.muscle == m }.maxOfOrNull { it.level } ?: 0f
    private fun MuscleAnalysis.level(m: Muscle, s: Side) = activities.firstOrNull { it.muscle == m && it.side == s }?.level ?: 0f
    private fun MuscleAnalysis.has(m: Muscle) = activities.any { it.muscle == m }
    private fun MuscleAnalysis.describe() = activities.joinToString { "${it.side.name[0]}-${it.muscle.name} ${"%.2f".format(it.level)} ${it.role} ${it.contraction}" }

    @Test fun standingNeedsLittleEffort() {
        val (a, _) = hold(PoseSynth.standing())
        assertEquals("Standing", a.movement.title)
        for (m in listOf(Muscle.QUADRICEPS, Muscle.GLUTEUS_MAXIMUS, Muscle.PECTORALIS_MAJOR, Muscle.LATERAL_DELTOID, Muscle.RECTUS_ABDOMINIS))
            assertTrue("$m in ${a.describe()}", a.level(m) < .25f)
    }

    @Test fun squatLoadsKneeAndHipExtensors() {
        val (a, p) = hold(PoseSynth.squat())
        assertEquals(a.describe(), "Squat", a.movement.title)
        assertTrue(a.describe(), a.level(Muscle.QUADRICEPS) > .5f)
        assertTrue(a.describe(), a.has(Muscle.GLUTEUS_MAXIMUS))
        assertTrue(a.describe(), a.has(Muscle.ERECTOR_SPINAE))
        assertFalse(a.describe(), a.has(Muscle.RECTUS_ABDOMINIS))
        assertEquals(MuscleRole.PRIME, a.activities.first { it.muscle == Muscle.QUADRICEPS }.role)
        // Both feet carry the body, roughly evenly.
        val feet = p.mech!!.contacts.filter { it.point == ContactPoint.HEEL || it.point == ContactPoint.TOE }
        assertEquals(1f, feet.sumOf { it.share.toDouble() }.toFloat(), .02f)
    }

    @Test fun hipHingeIsPosteriorChain() {
        val (a, _) = hold(PoseSynth.hinge())
        assertEquals(a.describe(), "Hip hinge", a.movement.title)
        assertTrue(a.describe(), a.level(Muscle.ERECTOR_SPINAE) > .6f)
        assertTrue(a.describe(), a.has(Muscle.GLUTEUS_MAXIMUS))
        assertTrue(a.describe(), a.has(Muscle.HAMSTRINGS))
        assertTrue(a.describe(), a.level(Muscle.QUADRICEPS) < a.level(Muscle.GLUTEUS_MAXIMUS))
    }

    @Test fun lateralRaiseIsLateralDeltoid() {
        val (a, _) = hold(PoseSynth.lateralRaise())
        val top = a.entries.first()
        assertEquals(a.describe(), Muscle.LATERAL_DELTOID, top.muscle)
        assertEquals(setOf(Side.LEFT, Side.RIGHT), top.sides)
        assertTrue(a.describe(), a.activities.any { it.muscle == Muscle.ROTATOR_CUFF && it.role == MuscleRole.STABILIZER })
        assertTrue(a.describe(), a.level(Muscle.QUADRICEPS) < .25f)
    }

    @Test fun frontRaiseIsAnteriorDeltoid() {
        val (a, _) = hold(PoseSynth.frontRaise())
        assertEquals(a.describe(), Muscle.ANTERIOR_DELTOID, a.entries.first().muscle)
        assertTrue(a.describe(), a.has(Muscle.PECTORALIS_MAJOR))
    }

    @Test fun overheadReachRecruitsScapularRotators() {
        val (a, _) = hold(PoseSynth.overhead())
        assertEquals("Overhead reach", a.movement.title)
        assertTrue(a.describe(), a.has(Muscle.UPPER_TRAPEZIUS) || a.has(Muscle.SERRATUS_ANTERIOR))
    }

    @Test fun curlHoldUsesElbowFlexorsNotTriceps() {
        val (a, _) = hold(PoseSynth.curl())
        assertTrue(a.describe(), a.has(Muscle.BICEPS_BRACHII))
        assertTrue(a.describe(), a.has(Muscle.BRACHIALIS))
        assertFalse(a.describe(), a.has(Muscle.TRICEPS_BRACHII))
        assertTrue(a.movement.title, a.movement.title.contains("curl"))
    }

    @Test fun highPlankBracesCoreAndShoulders() {
        val (a, p) = hold(PoseSynth.highPlank())
        assertEquals(a.describe(), "High plank", a.movement.title)
        assertTrue(a.describe(), a.level(Muscle.RECTUS_ABDOMINIS) > .4f)
        assertTrue(a.describe(), a.has(Muscle.SERRATUS_ANTERIOR))
        assertTrue(a.describe(), a.has(Muscle.QUADRICEPS) || a.has(Muscle.ILIOPSOAS))
        // Hands carry roughly 60-70 % of body weight in a plank (published push-up force-plate data: ~64-69 %).
        val hands = p.mech!!.contacts.filter { it.point == ContactPoint.HAND }.sumOf { it.share.toDouble() }
        assertTrue("hands=$hands", hands in .55..0.8)
    }

    @Test fun pushUpBottomIsChestAndTriceps() {
        val (a, _) = hold(PoseSynth.pushUpBottom())
        assertEquals(a.describe(), "Push-up", a.movement.title)
        assertTrue(a.describe(), a.has(Muscle.TRICEPS_BRACHII))
        assertTrue(a.describe(), a.has(Muscle.PECTORALIS_MAJOR) || a.has(Muscle.ANTERIOR_DELTOID))
        assertTrue(a.describe(), a.has(Muscle.RECTUS_ABDOMINIS))
    }

    @Test fun forearmPlankIsCore() {
        val (a, _) = hold(PoseSynth.forearmPlank())
        assertEquals(a.describe(), "Forearm plank", a.movement.title)
        assertTrue(a.describe(), a.level(Muscle.RECTUS_ABDOMINIS) > .5f)
        assertTrue(a.describe(), a.has(Muscle.TRANSVERSUS_ABDOMINIS))
    }

    @Test fun calfRaiseLoadsPlantarflexors() {
        val (a, _) = hold(PoseSynth.calfRaise())
        assertEquals(a.describe(), "Calf raise", a.movement.title)
        assertTrue(a.describe(), a.level(Muscle.GASTROCNEMIUS) > .5f)
        assertTrue(a.describe(), a.has(Muscle.SOLEUS))
    }

    @Test fun singleLegStanceUsesStanceSideAbductors() {
        val (a, _) = hold(PoseSynth.singleLeg())
        assertTrue(a.movement.title, a.movement.title == "Knee raise" || a.movement.title == "Single-leg stance")
        // Left knee is raised: the right (stance) gluteus medius keeps the pelvis level.
        assertTrue(a.describe(), a.level(Muscle.GLUTEUS_MEDIUS, Side.RIGHT) > .3f)
        assertTrue(a.describe(), a.level(Muscle.GLUTEUS_MEDIUS, Side.RIGHT) > a.level(Muscle.GLUTEUS_MEDIUS, Side.LEFT))
        assertTrue(a.describe(), a.has(Muscle.ILIOPSOAS))
    }

    @Test fun oneSidedRaiseOnlyLightsThatSide() {
        val pose = SynthPose(upperArmR = PoseSynth.dir(0f, towardOut = 90f), forearmR = PoseSynth.dir(0f, towardOut = 90f),
            upperArmL = V3(-.12f, -1f, 0f), forearmL = V3(-.05f, -1f, .1f))
        val (a, _) = hold(pose)
        assertTrue(a.describe(), a.level(Muscle.LATERAL_DELTOID, Side.RIGHT) > .3f)
        assertTrue(a.describe(), a.level(Muscle.LATERAL_DELTOID, Side.LEFT) < .1f)
        assertEquals("Right arm raise", a.movement.title)
        assertTrue(a.regions.getOrDefault("delt_side_r", 0f) > .3f)
        assertFalse(a.regions.containsKey("delt_side_l"))
    }

    @Test fun tiltedPhoneIsCompensatedByGravity() {
        val (level, _) = hold(PoseSynth.squat())
        val (tilted, _) = hold(PoseSynth.squat(), tilt = 18f)
        assertEquals(level.describe() + " | " + tilted.describe(), level.level(Muscle.QUADRICEPS), tilted.level(Muscle.QUADRICEPS), .1f)
        assertEquals("Squat", tilted.movement.title)
    }

    @Test fun squatDescentIsEccentricAndAscentConcentric() {
        val p = Pipeline()
        var down: MuscleAnalysis? = null; var up: MuscleAnalysis? = null
        // 1.2 s descent, 1.2 s ascent at 30 fps.
        for (i in 0..72) {
            val t = 1000L + i * 33L
            val depth = if (i <= 36) i / 36f else (72 - i) / 36f
            val a = p.step(PoseSynth.frame(PoseSynth.squat(depth), t))
            if (i == 28) down = a
            if (i == 46) up = a
        }
        val qDown = down!!.activities.first { it.muscle == Muscle.QUADRICEPS }
        val qUp = up!!.activities.first { it.muscle == Muscle.QUADRICEPS }
        assertEquals(Contraction.ECCENTRIC, qDown.contraction)
        assertEquals(Contraction.CONCENTRIC, qUp.contraction)
        assertEquals("Lowering", down!!.movement.phase)
        assertEquals("Rising", up!!.movement.phase)
    }

    @Test fun skeletonKeepsProportionsAndStandsOnTheFloor() {
        val (_, p) = hold(PoseSynth.standing())
        val b = p.body!!
        assertEquals(0f, minOf(b.left.heel.y, b.right.heel.y, b.left.toe.y, b.right.toe.y), .02f)
        assertEquals(.92f, b.pelvis.y, .05f)
        assertEquals(BodyDims.THIGH, (b.left.knee - b.left.hip).length(), .001f)
        assertEquals(BodyDims.FOREARM, (b.right.wrist - b.right.elbow).length(), .001f)
        // Facing the camera: the subject's forward points toward the camera (+z) and their right toward screen-left.
        assertTrue(b.chestForward.z > .9f)
        assertTrue(b.chestRight.x < -.9f)
    }

    @Test fun legsOutOfFrameAreNotReported() {
        val legs = setOf(25, 26, 27, 28, 29, 30, 31, 32)
        val (a, p) = hold(PoseSynth.squat(), hidden = legs)
        assertFalse(p.body!!.isTracked(Chain.LEG_L))
        assertFalse(a.describe(), a.has(Muscle.QUADRICEPS))
    }

    @Test fun supportIsDistributedUnderTheCentreOfMass() {
        val b = Biomechanics()
        val w = b.distribute(listOf(V3(0f, 0f, 0f), V3(1f, 0f, 0f)), V3(.7f, 1f, 0f))
        assertEquals(.3f, w[0], .03f); assertEquals(.7f, w[1], .03f)
        val outside = b.distribute(listOf(V3(0f, 0f, 0f), V3(1f, 0f, 0f)), V3(2f, 1f, 0f))
        assertEquals(1f, outside[1], .02f)
        assertTrue(abs(outside.sum() - 1f) < 1e-3f)
    }
}
