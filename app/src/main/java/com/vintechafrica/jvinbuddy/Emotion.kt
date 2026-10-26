package com.vintechafrica.jvinbuddy

/**
 * One emotion drives three outputs: the face, a movement, and the voice.
 * eyeOpen 0..1, mouthCurve -1 (frown) .. 1 (smile), browTilt -1..1 (+ = sad, - = surprised).
 */
enum class Emotion(
    val eyeOpen: Float,
    val mouthCurve: Float,
    val browTilt: Float,
    val mouthO: Boolean,
    val color: Long,
    val pitch: Float,
    val rate: Float,
) {
    NEUTRAL(0.85f, 0.35f, 0f, false, 0xFF4FC3F7, 1.15f, 1.0f),
    HAPPY(0.75f, 1.0f, 0f, false, 0xFF4CD9A0, 1.35f, 1.1f),
    CURIOUS(1.0f, 0.15f, -0.3f, false, 0xFF4FC3F7, 1.25f, 1.0f),
    BORED(0.5f, -0.1f, 0f, false, 0xFF90A4AE, 1.0f, 0.85f),
    SLEEPY(0.12f, 0f, 0f, false, 0xFF7986CB, 0.9f, 0.75f),
    SURPRISED(1.0f, 0f, -0.6f, true, 0xFFFFD54F, 1.5f, 1.2f),
    SAD(0.6f, -0.9f, 0.6f, false, 0xFF64B5F6, 0.9f, 0.85f),
    TIRED(0.4f, -0.4f, 0.2f, false, 0xFFFF8A65, 0.85f, 0.8f),
    THINKING(0.7f, 0f, 0.3f, false, 0xFFBA68C8, 1.1f, 1.0f),
    DEAD(1.0f, -0.3f, 0f, false, 0xFFE57373, 0.75f, 0.8f); // X eyes, drawn in RobotFace

    companion object {
        fun parse(s: String?): Emotion =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NEUTRAL
    }
}
