package com.vintechafrica.jvinbuddy

import android.content.Context

/** Settings saved on the phone. Nothing secret lives in the code or the repo. */
class Prefs(context: Context) {
    companion object {
        /** Gemini 2.5 models are closed to new projects (Oct 2026). Fast, cheap 3.x model for spoken replies. */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }

    private val sp = context.getSharedPreferences("jvinbuddy", Context.MODE_PRIVATE)

    var workerUrl: String
        get() = sp.getString("workerUrl", "") ?: ""
        set(v) = sp.edit().putString("workerUrl", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Groq API key from console.groq.com (free). Stored only on this phone. */
    var groqKey: String
        get() = sp.getString("groqKey", "") ?: ""
        set(v) = sp.edit().putString("groqKey", v.trim()).apply()

    /** Jamendo client ID (free, devportal.jamendo.com) for streaming Creative Commons music. */
    var jamendoId: String
        get() = sp.getString("jamendoId", "") ?: ""
        set(v) = sp.edit().putString("jamendoId", v.trim()).apply()

    /** Gemini API key from aistudio.google.com. Stored only on this phone. */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("geminiModel", v.trim()).apply()

    /** BLE address of the paired body. Saved after the first scan; empty = scan again. */
    var robotAddress: String
        get() = sp.getString("robotAddress", "") ?: ""
        set(v) = sp.edit().putString("robotAddress", v.trim()).apply()

    /** Flip if the robot turns away from your face instead of toward it. */
    var invertSteer: Boolean
        get() = sp.getBoolean("invertSteer", false)
        set(v) = sp.edit().putBoolean("invertSteer", v).apply()

    /** Low battery threshold in volts. 6.6 suits a 2S Li-ion pack. */
    var lowBattery: Float
        get() = sp.getFloat("lowBattery", 6.6f)
        set(v) = sp.edit().putFloat("lowBattery", v).apply()

    /** Motor speed, 25..100 %. Moves run longer at low speed so turns keep their size. */
    var speedPercent: Int
        get() = sp.getInt("speedPercent", 55)
        set(v) = sp.edit().putInt("speedPercent", v.coerceIn(25, 100)).apply()

    /** Hand gesture tricks. Turn off to save battery and CPU. */
    var gestures: Boolean
        get() = sp.getBoolean("gestures", true)
        set(v) = sp.edit().putBoolean("gestures", v).apply()

    /** True: commands only work after "hey vinbot" (except stop). False: always listening for commands. */
    var wakeRequired: Boolean
        get() = sp.getBoolean("wakeRequired", true)
        set(v) = sp.edit().putBoolean("wakeRequired", v).apply()

    /** Your birthday as MM-dd, e.g. 03-15. Empty = unknown. */
    var birthday: String
        get() = sp.getString("birthday", "") ?: ""
        set(v) = sp.edit().putString("birthday", v.trim()).apply()

    // ---------------------------------------------------------------- memory that grows
    fun getInt(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun getFloat(key: String, def: Float) = sp.getFloat(key, def)
    fun putFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    fun getString(key: String, def: String) = sp.getString(key, def) ?: def
    fun putString(key: String, v: String) = sp.edit().putString(key, v).apply()

    /** Off (default): wheels move only after a command, gesture or game. On: follows faces and wanders by itself. */
    var autoMove: Boolean
        get() = sp.getBoolean("autoMove", false)
        set(v) = sp.edit().putBoolean("autoMove", v).apply()

    /** Screen brightness 0.1..1. Lower = less AMOLED burn-in and longer battery. */
    var brightness: Float
        get() = sp.getFloat("brightness", 0.55f)
        set(v) = sp.edit().putFloat("brightness", v.coerceIn(0.1f, 1f)).apply()

    /** Always show the status line. Off: it shows for 30 s after start or settings, then hides (burn-in). */
    /** Story mode script (see Story.kt for the step words). */
    var storyScript: String
        get() = sp.getString("storyScript", null) ?: Story.DEFAULT
        set(v) = sp.edit().putString("storyScript", v).apply()

    var showStatus: Boolean
        get() = sp.getBoolean("showStatus2", false)
        set(v) = sp.edit().putBoolean("showStatus2", v).apply()
}
