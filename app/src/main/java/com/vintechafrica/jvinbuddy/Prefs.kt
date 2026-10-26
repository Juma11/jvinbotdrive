package com.vintechafrica.jvinbuddy

import android.content.Context

/** Settings saved on the phone. Nothing secret lives in the code or the repo. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jvinbuddy", Context.MODE_PRIVATE)

    var workerUrl: String
        get() = sp.getString("workerUrl", "") ?: ""
        set(v) = sp.edit().putString("workerUrl", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Gemini API key from aistudio.google.com. Stored only on this phone. */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", "gemini-2.5-flash") ?: "gemini-2.5-flash"
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

    var showStatus: Boolean
        get() = sp.getBoolean("showStatus", true)
        set(v) = sp.edit().putBoolean("showStatus", v).apply()
}
