package com.vintechafrica.jvinbuddy

/**
 * VinBot's inner state, saved on the phone so it carries over between days.
 *
 *  social  0..100  goes down when nobody is around, up with faces, petting, talking
 *  fun     0..100  goes down slowly, up with games and tricks
 *  energy  0..100  goes down with driving and tricks, up while asleep
 *
 * Plus long-term memory:
 *  interactions  total count of things you did with it (personality grows with it)
 *  trick weights how much you liked each trick (praise learning)
 */
class Needs(private val prefs: Prefs) {

    var social = prefs.getFloat("need_social", 60f)
        private set
    var fun_ = prefs.getFloat("need_fun", 60f)
        private set
    var energy = prefs.getFloat("need_energy", 80f)
        private set
    var interactions = prefs.getInt("interactions", 0)
        private set

    private val weights: MutableMap<String, Int> = prefs.getString("trick_weights", "")
        .split(";").mapNotNull {
            val kv = it.split("=")
            if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: 3) else null
        }.toMap().toMutableMap()

    /** Chattiness grows from 0.3 (new robot) to 1.0 (after about 200 interactions). */
    val chattiness: Float get() = (0.3f + interactions / 300f).coerceAtMost(1f)

    // ---------------------------------------------------------------- per-second update
    fun tick(seconds: Float, faceVisible: Boolean, asleep: Boolean, driving: Boolean) {
        social += if (faceVisible) 0.6f * seconds else -0.08f * seconds
        fun_ -= 0.03f * seconds
        energy += when {
            asleep -> 0.25f * seconds
            driving -> -0.05f * seconds
            else -> -0.01f * seconds
        }
        clamp()
    }

    fun interacted(socialGain: Float = 4f, funGain: Float = 0f, energyCost: Float = 0f): Int {
        social += socialGain
        fun_ += funGain
        energy -= energyCost
        clamp()
        interactions++
        return interactions
    }

    fun refill() {
        energy = 100f
    }

    private fun clamp() {
        social = social.coerceIn(0f, 100f)
        fun_ = fun_.coerceIn(0f, 100f)
        energy = energy.coerceIn(0f, 100f)
    }

    fun save() {
        prefs.putFloat("need_social", social)
        prefs.putFloat("need_fun", fun_)
        prefs.putFloat("need_energy", energy)
        prefs.putInt("interactions", interactions)
        prefs.putString("trick_weights", weights.entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    /** One word for how it feels, used when you ask "how do you feel". */
    fun mood(): String = when {
        energy < 25f -> "tired"
        social < 30f -> "lonely"
        fun_ < 30f -> "bored"
        social > 70f && fun_ > 60f -> "great"
        else -> "okay"
    }

    // ---------------------------------------------------------------- praise learning
    fun weight(trick: String): Int = weights[trick] ?: 3

    /** Returns the new weight. Liked tricks go up to 12, disliked down to 1. */
    fun reinforce(trick: String, liked: Boolean): Int {
        val w = (weight(trick) + if (liked) 2 else -1).coerceIn(1, 12)
        weights[trick] = w
        return w
    }

    fun pickWeighted(tricks: List<String>): String {
        val total = tricks.sumOf { weight(it) }
        var r = (Math.random() * total).toInt()
        for (t in tricks) {
            r -= weight(t)
            if (r < 0) return t
        }
        return tricks.last()
    }

    fun favourite(tricks: List<String>): String? = tricks.maxByOrNull { weight(it) }?.takeIf { weight(it) > 3 }
}
