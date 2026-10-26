package com.vintechafrica.jvinbuddy

/**
 * One emotion drives the face, a movement, and the voice.
 *
 * Face (Emo style): eyes are rounded blocks, and black "eyelids" slide over them.
 *  eyeOpen    0..1   eye height
 *  lidTop     0..1   how far the top lid comes down
 *  lidTilt   -1..1   top lid slant: + = inner corner low (angry), - = inner corner high (sad)
 *  lidBottom  0..1   bottom lid pushes up in a curve (happy squint)
 *  mouthCurve -1..1  frown .. smile
 *  browTilt          only used for raised brows (negative = raised)
 */
enum class Emotion(
    val eyeOpen: Float,
    val mouthCurve: Float,
    val browTilt: Float,
    val mouthO: Boolean,
    val color: Long,
    val pitch: Float,
    val rate: Float,
    val lidTop: Float = 0f,
    val lidTilt: Float = 0f,
    val lidBottom: Float = 0f,
) {
    NEUTRAL(0.85f, 0.35f, 0f, false, 0xFF4FC3F7, 1.15f, 1.0f),
    HAPPY(0.8f, 1.0f, 0f, false, 0xFF4CD9A0, 1.35f, 1.1f, lidBottom = 0.45f),
    CURIOUS(1.0f, 0.15f, -0.3f, false, 0xFF4FC3F7, 1.25f, 1.0f),
    BORED(0.75f, -0.1f, 0f, false, 0xFF90A4AE, 1.0f, 0.85f, lidTop = 0.5f),
    SLEEPY(0.15f, 0f, 0f, false, 0xFF7986CB, 0.9f, 0.75f),
    SURPRISED(1.0f, 0f, -0.6f, true, 0xFFFFD54F, 1.5f, 1.2f),
    SAD(0.8f, -0.9f, 0f, false, 0xFF64B5F6, 0.9f, 0.85f, lidTop = 0.3f, lidTilt = -0.9f),
    TIRED(0.6f, -0.4f, 0f, false, 0xFFFF8A65, 0.85f, 0.8f, lidTop = 0.35f, lidTilt = -0.3f),
    THINKING(0.8f, 0f, 0f, false, 0xFFBA68C8, 1.1f, 1.0f, lidTop = 0.2f),
    DEAD(1.0f, -0.3f, 0f, false, 0xFFE57373, 0.75f, 0.8f),            // X eyes
    LOVE(1.0f, 1.0f, 0f, false, 0xFFFF6FA8, 1.4f, 1.0f),              // heart eyes
    DIZZY(1.0f, -0.2f, 0f, true, 0xFFFFB74D, 1.2f, 0.9f),             // spiral eyes
    SINGING(0.8f, 1.0f, 0f, false, 0xFF26C6DA, 1.3f, 1.0f, lidBottom = 0.45f),
    ANGRY(0.85f, -0.6f, 0f, false, 0xFFFF5252, 0.8f, 1.15f, lidTop = 0.4f, lidTilt = 1.0f),
    SCARED(1.0f, -0.4f, -0.5f, true, 0xFFB39DDB, 1.6f, 1.25f, lidTop = 0.1f, lidTilt = -0.6f),
    LAUGH(0.75f, 1.0f, 0f, false, 0xFFAEEA00, 1.45f, 1.15f, lidBottom = 0.55f),
    SHY(0.75f, 0.4f, 0f, false, 0xFFFF80AB, 1.3f, 0.9f, lidTop = 0.15f, lidBottom = 0.35f),
    EXCITED(1.0f, 1.0f, 0f, true, 0xFFFFD740, 1.5f, 1.15f),           // star eyes
    CRYING(0.7f, -1.0f, 0f, false, 0xFF64B5F6, 0.85f, 0.8f, lidTop = 0.35f, lidTilt = -1.0f);

    companion object {
        fun parse(s: String?): Emotion =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NEUTRAL
    }
}
