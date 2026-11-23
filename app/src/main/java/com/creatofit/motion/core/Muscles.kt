package com.creatofit.motion.core

enum class MuscleGroup(val label: String) {
    CHEST("Chest"), SHOULDERS("Shoulders"), ARMS("Arms"), BACK("Back"), CORE("Core"), HIPS("Hips"), THIGHS("Thighs"), LOWER_LEG("Lower leg")
}

/**
 * Muscle catalogue. [region] names the avatar highlight patch (suffixed _l / _r in the mesh);
 * deep muscles without their own surface patch highlight the overlying region or none at all.
 * Anatomical functions follow standard references (e.g. Moore's Clinically Oriented Anatomy;
 * Neumann, Kinesiology of the Musculoskeletal System).
 */
enum class Muscle(
    val label: String,
    /** Compact name for labels on the 3D model. */
    val short: String,
    val group: MuscleGroup,
    val region: String?,
    val location: String,
    val function: String,
    val deep: Boolean = false,
) {
    PECTORALIS_MAJOR("Pectoralis major", "Pecs", MuscleGroup.CHEST, "pec",
        "Front of the chest",
        "Draws the arm forward and across the body (horizontal adduction); flexes and adducts the shoulder."),
    ANTERIOR_DELTOID("Anterior deltoid", "Front delts", MuscleGroup.SHOULDERS, "delt_front",
        "Front of the shoulder",
        "Raises the arm forward (shoulder flexion) and assists pressing and pushing."),
    LATERAL_DELTOID("Lateral deltoid", "Side delts", MuscleGroup.SHOULDERS, "delt_side",
        "Outer shoulder",
        "Lifts the arm out to the side (shoulder abduction)."),
    POSTERIOR_DELTOID("Posterior deltoid", "Rear delts", MuscleGroup.SHOULDERS, "delt_rear",
        "Back of the shoulder",
        "Draws the arm backward (extension) and out to the side behind the body (horizontal abduction)."),
    ROTATOR_CUFF("Rotator cuff", "Rotator cuff", MuscleGroup.SHOULDERS, null,
        "Deep around the shoulder blade (supraspinatus, infraspinatus, teres minor, subscapularis)",
        "Keeps the head of the humerus centred in the shoulder socket; supraspinatus starts abduction.", deep = true),
    UPPER_TRAPEZIUS("Upper trapezius", "Upper traps", MuscleGroup.BACK, "trap_upper",
        "Top of the shoulders and back of the neck",
        "Elevates and upwardly rotates the shoulder blade so the arm can reach overhead."),
    RHOMBOIDS("Rhomboids & middle trapezius", "Rhomboids", MuscleGroup.BACK, "trap_mid",
        "Between the shoulder blades",
        "Squeeze the shoulder blades together (retraction) and hold them against the ribcage."),
    LATISSIMUS_DORSI("Latissimus dorsi", "Lats", MuscleGroup.BACK, "lats",
        "Broad muscle on the sides of the back",
        "Pulls the arm down and back (shoulder extension and adduction), as in rows and pull-ups."),
    SERRATUS_ANTERIOR("Serratus anterior", "Serratus", MuscleGroup.CHEST, "serratus",
        "Side of the ribcage, under the armpit",
        "Pushes the shoulder blade forward around the ribcage (protraction) and rotates it upward."),
    BICEPS_BRACHII("Biceps brachii", "Biceps", MuscleGroup.ARMS, "biceps",
        "Front of the upper arm",
        "Bends the elbow and turns the palm up; assists shoulder flexion."),
    BRACHIALIS("Brachialis", "Brachialis", MuscleGroup.ARMS, "biceps",
        "Beneath the biceps, lower half of the upper arm",
        "The prime elbow flexor in every forearm position.", deep = true),
    BRACHIORADIALIS("Brachioradialis", "Forearm", MuscleGroup.ARMS, "forearm_flex",
        "Thumb side of the forearm",
        "Bends the elbow, strongest with the thumb pointing up."),
    TRICEPS_BRACHII("Triceps brachii", "Triceps", MuscleGroup.ARMS, "triceps",
        "Back of the upper arm",
        "Straightens the elbow; the long head also extends the shoulder."),
    RECTUS_ABDOMINIS("Rectus abdominis", "Abs", MuscleGroup.CORE, "abs",
        "Front of the abdomen",
        "Flexes the trunk and resists the lower back arching."),
    OBLIQUES("External & internal obliques", "Obliques", MuscleGroup.CORE, "obliques",
        "Sides of the abdomen",
        "Rotate and side-bend the trunk, and brace the core against twisting."),
    TRANSVERSUS_ABDOMINIS("Transversus abdominis", "Deep core", MuscleGroup.CORE, null,
        "Deepest abdominal layer, wrapping the waist",
        "Compresses the abdomen like a corset to stiffen the spine.", deep = true),
    ERECTOR_SPINAE("Erector spinae", "Erectors", MuscleGroup.BACK, "erector",
        "Columns either side of the spine",
        "Extends the spine and holds the trunk up against gravity."),
    QUADRATUS_LUMBORUM("Quadratus lumborum", "QL", MuscleGroup.BACK, null,
        "Deep in the lower back, between the ribs and pelvis",
        "Bends the trunk sideways and hitches the hip.", deep = true),
    GLUTEUS_MAXIMUS("Gluteus maximus", "Glutes", MuscleGroup.HIPS, "glute_max",
        "Buttocks",
        "Extends the hip — driving you up from a squat or back from a hinge."),
    GLUTEUS_MEDIUS("Gluteus medius & minimus", "Glute med", MuscleGroup.HIPS, "glute_med",
        "Upper outer hip",
        "Lifts the leg out to the side and keeps the pelvis level when standing on one leg."),
    ILIOPSOAS("Hip flexors (iliopsoas)", "Hip flexors", MuscleGroup.HIPS, "hip_flexor",
        "Front of the hip, deep to the groin",
        "Lifts the thigh toward the trunk (hip flexion)."),
    QUADRICEPS("Quadriceps", "Quads", MuscleGroup.THIGHS, "quads",
        "Front of the thigh",
        "Straightens the knee; rectus femoris also helps flex the hip."),
    HAMSTRINGS("Hamstrings", "Hamstrings", MuscleGroup.THIGHS, "hamstrings",
        "Back of the thigh",
        "Bend the knee and extend the hip."),
    ADDUCTORS("Adductors", "Adductors", MuscleGroup.THIGHS, "adductors",
        "Inner thigh",
        "Pull the leg toward the midline; adductor magnus also extends the hip."),
    GASTROCNEMIUS("Gastrocnemius", "Calves", MuscleGroup.LOWER_LEG, "calves",
        "Upper calf",
        "Points the foot (plantarflexion) and assists knee flexion."),
    SOLEUS("Soleus", "Soleus", MuscleGroup.LOWER_LEG, "calves",
        "Lower calf, beneath the gastrocnemius",
        "Points the foot, especially with the knee bent; key for standing balance."),
    TIBIALIS_ANTERIOR("Tibialis anterior", "Tibialis", MuscleGroup.LOWER_LEG, "tibialis",
        "Front of the shin",
        "Lifts the foot and toes (dorsiflexion).");
}

enum class MuscleRole(val label: String) { PRIME("Prime mover"), SYNERGIST("Synergist"), STABILIZER("Stabilizer") }

enum class Contraction(val label: String, val verb: String) {
    CONCENTRIC("Concentric", "Shortening to produce"),
    ECCENTRIC("Eccentric", "Lengthening to control"),
    ISOMETRIC("Isometric", "Holding against"),
}

enum class Intensity(val label: String) { LIGHT("Light"), MODERATE("Moderate"), HIGH("High");
    companion object { fun of(level: Float) = when { level >= .66f -> HIGH; level >= .33f -> MODERATE; else -> LIGHT } }
}
