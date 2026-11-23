package com.creatofit.motion.core

enum class PoseModel(val asset: String, val label: String, val description: String) {
    FULL("pose_landmarker_full.task", "MediaPipe Full", "Recommended · 33 joints with 3D depth, hands and feet"),
    HEAVY("pose_landmarker_heavy.task", "MediaPipe Heavy", "Most accurate 3D model · slower on most phones"),
    LITE("pose_landmarker_lite.task", "MediaPipe Lite", "Fastest 3D model · lower precision"),
    MOVENET_THUNDER("movenet_thunder_float16_v4.tflite", "MoveNet Thunder", "2D only · no depth, hands or feet"),
    MOVENET_LIGHTNING("movenet_lightning_float16_v4.tflite", "MoveNet Lightning", "2D only · fastest, lowest precision");
    val hasWorldLandmarks get() = this == FULL || this == HEAVY || this == LITE
    val inputSize get() = if (this == MOVENET_LIGHTNING) 192 else 256
    val scoreFloor get() = if (hasWorldLandmarks) .5f else .30f
    val summary get() = if (!hasWorldLandmarks) "$label · float16 · v4 · 2D · tracked crop" else "Pose Landmarker ${label.removePrefix("MediaPipe ")} · float16 · 3D"
    companion object { fun fromSetting(value: String?) = entries.firstOrNull { it.name == value } ?: FULL }
}
