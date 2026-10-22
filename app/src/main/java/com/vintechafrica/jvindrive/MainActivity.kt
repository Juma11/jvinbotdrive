package com.vintechafrica.jvindrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("drive", Context.MODE_PRIVATE)
    val SPEEDS = floatArrayOf(0.4f, 0.7f, 1.0f)
    val RANGES = floatArrayOf(6.0f, 4.5f, 3.0f)          // m/s² of tilt for full speed
    val RANGE_NAMES = arrayOf("Low", "Medium", "High")

    fun isWatch(c: Context) = c.resources.configuration.smallestScreenWidthDp < 320
    const val TILT = 0
    const val PAD = 1
    const val BUTTONS = 2
    val MODE_NAMES = arrayOf("Tilt", "Pad", "Buttons")
    fun mode(c: Context) = p(c).getInt("mode", if (isWatch(c)) TILT else PAD)
    fun setMode(c: Context, v: Int) = p(c).edit().putInt("mode", v).apply()
    fun tilt(c: Context) = mode(c) == TILT
    fun speedIdx(c: Context) = p(c).getInt("speed", 0)
    fun setSpeedIdx(c: Context, v: Int) = p(c).edit().putInt("speed", v).apply()
    fun sensIdx(c: Context) = p(c).getInt("sens", 1)
    fun setSensIdx(c: Context, v: Int) = p(c).edit().putInt("sens", v).apply()
    fun invFwd(c: Context) = p(c).getBoolean("invFwd", false)
    fun setInvFwd(c: Context, v: Boolean) = p(c).edit().putBoolean("invFwd", v).apply()
    fun invTurn(c: Context) = p(c).getBoolean("invTurn", false)
    fun setInvTurn(c: Context, v: Boolean) = p(c).edit().putBoolean("invTurn", v).apply()
}

class MainActivity : Activity(), SensorEventListener {

    private lateinit var status: TextView
    private lateinit var speedChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var stage: FrameLayout
    private lateinit var readout: TextView
    private var holdButton: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sensors: SensorManager? = null

    // Low-pass filtered gravity, and the pose captured when the driver presses.
    private var ax = 0f
    private var ay = 0f
    private var ax0 = 0f
    private var ay0 = 0f
    private var haveSample = false
    private var holding = false

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(SensorManager::class.java)

        val watch = Prefs.isWatch(this)
        val root = vbox().apply {
            setBackgroundColor(C.BG)
            setPadding(dp(12), dp(if (watch) 8 else 20), dp(12), dp(10))
        }

        // Top bar: link status, speed, mode
        status = chip("● Looking for car", C.CARD2)
        speedChip = chip("", C.CARD2) {
            Prefs.setSpeedIdx(this, (Prefs.speedIdx(this) + 1) % Prefs.SPEEDS.size)
            renderChips()
        }
        modeChip = chip("", C.CARD2) {
            if (holding || Car.driving) return@chip
            Prefs.setMode(this, (Prefs.mode(this) + 1) % Prefs.MODE_NAMES.size)
            buildStage()
        }
        root.addView(hbox().apply {
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(modeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        })

        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        readout = text("L 0   R 0", 13f, C.SUB).apply { gravity = Gravity.CENTER }
        root.addView(readout)

        root.addView(hbox().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            addView(chip("Wi-Fi", C.CARD) { openWifi() })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(8), 1))
            addView(chip("Settings", C.CARD) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
        })

        setContentView(root)
        buildStage()
    }

    override fun onResume() {
        super.onResume()
        Car.start(this)
        renderChips()
        if (Prefs.tilt(this)) registerTilt()
        handler.post(tick)
    }

    override fun onPause() {
        release()
        sensors?.unregisterListener(this)
        handler.removeCallbacks(tick)
        Car.stop(this)
        super.onPause()
    }

    // ---------- stage: tilt button or joystick ----------

    private fun buildStage() {
        stage.removeAllViews()
        holdButton = null
        sensors?.unregisterListener(this)
        renderChips()
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.62f).toInt()

        if (Prefs.tilt(this)) {
            registerTilt()
            val b = text("HOLD\nTO DRIVE", 18f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(C.CARD, C.CYAN, 3)
                setOnTouchListener { v, e -> onHold(v, e) }
            }
            holdButton = b
            stage.addView(b, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        } else if (Prefs.mode(this) == Prefs.BUTTONS) {
            stage.addView(buildButtons(size), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        } else {
            val pad = PadView(this) { x, y, down ->
                if (down && !Car.driving) stage.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                drive(y, x, down)
            }
            stage.addView(pad, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
    }

    // ---------- buttons ----------

    private var pressed = 0               // bit mask of held arrows
    private val UP = 1
    private val DOWN = 2
    private val LEFT = 4
    private val RIGHT = 8

    /** Cross of four hold-to-drive arrows with a STOP in the middle. */
    private fun buildButtons(size: Int): View {
        val cell = size / 3
        val grid = FrameLayout(this)
        fun arrow(label: String, bit: Int, col: Int, row: Int) {
            val b = text(label, 26f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(C.CARD2, 20)
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (pressed == 0) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            pressed = pressed or bit
                            background = rounded(C.CYAN, 20)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pressed = pressed and bit.inv()
                            background = rounded(C.CARD2, 20)
                        }
                    }
                    applyButtons()
                    true
                }
            }
            val pad = dp(3)
            grid.addView(b, FrameLayout.LayoutParams(cell - 2 * pad, cell - 2 * pad).apply {
                leftMargin = col * cell + pad
                topMargin = row * cell + pad
            })
        }
        arrow("▲", UP, 1, 0)
        arrow("◀", LEFT, 0, 1)
        arrow("▶", RIGHT, 2, 1)
        arrow("▼", DOWN, 1, 2)
        val stop = text("STOP", 13f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = oval(C.RED)
            setOnClickListener {
                pressed = 0
                applyButtons()
            }
        }
        val pad = dp(10)
        grid.addView(stop, FrameLayout.LayoutParams(cell - 2 * pad, cell - 2 * pad).apply {
            leftMargin = cell + pad
            topMargin = cell + pad
        })
        return grid
    }

    private fun applyButtons() {
        val fwd = (if (pressed and UP != 0) 1f else 0f) - (if (pressed and DOWN != 0) 1f else 0f)
        val side = (if (pressed and RIGHT != 0) 1f else 0f) - (if (pressed and LEFT != 0) 1f else 0f)
        if (fwd == 0f && side == 0f) {
            drive(0f, 0f, false)
            return
        }
        // Arrow alone spins in place; with forward or back it curves.
        val turn = if (fwd == 0f) side else side * 0.5f
        drive(fwd, turn, true)
    }

    private fun onHold(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!haveSample) return true
                // Your current pose becomes neutral. After any drop the car never moves until you press again.
                ax0 = ax
                ay0 = ay
                holding = true
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                holdButton?.background = oval(C.GREEN)
                holdButton?.text = "DRIVING"
                Car.left = 0
                Car.right = 0
                Car.driving = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        holding = false
        pressed = 0
        Car.driving = false
        Car.left = 0
        Car.right = 0
        holdButton?.background = oval(C.CARD, C.CYAN, 3)
        holdButton?.text = "HOLD\nTO DRIVE"
    }

    // ---------- tilt ----------

    private fun registerTilt() {
        val s = sensors ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        s.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = 0.25f
        if (!haveSample) {
            ax = e.values[0]
            ay = e.values[1]
            haveSample = true
        } else {
            ax += a * (e.values[0] - ax)
            ay += a * (e.values[1] - ay)
        }
        if (!holding) return
        val range = Prefs.RANGES[Prefs.sensIdx(this)]
        // Top edge down = forward. Right edge down = turn right.
        var fwd = -(ay - ay0) / range
        var turn = -(ax - ax0) / range
        if (Prefs.invFwd(this)) fwd = -fwd
        if (Prefs.invTurn(this)) turn = -turn
        drive(fwd, turn, true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- shared ----------

    private fun drive(fwdIn: Float, turnIn: Float, down: Boolean) {
        if (!down) {
            Car.driving = false
            Car.left = 0
            Car.right = 0
            return
        }
        val fwd = shape(fwdIn)
        val turn = shape(turnIn)
        val (l, r) = Car.mix(fwd, turn, Prefs.SPEEDS[Prefs.speedIdx(this)])
        Car.left = l
        Car.right = r
        Car.driving = true
    }

    /** Dead zone so a shaky hand does not creep the car, then clamp. */
    private fun shape(v: Float): Float {
        val dz = 0.12f
        if (abs(v) < dz) return 0f
        val s = (abs(v) - dz) / (1f - dz)
        return (if (v > 0) s else -s).coerceIn(-1f, 1f)
    }

    private fun renderChips() {
        speedChip.text = "${(Prefs.SPEEDS[Prefs.speedIdx(this)] * 100).toInt()}%"
        modeChip.text = Prefs.MODE_NAMES[Prefs.mode(this)]
    }

    private fun refreshStatus() {
        if (Car.online) {
            status.text = "● Car ${Car.rttMs} ms"
            status.background = rounded(if (Car.rttMs in 0..150) C.GREEN else C.ORANGE, 14)
        } else {
            status.text = "● No car"
            status.background = rounded(C.RED, 14)
        }
        readout.text = if (Car.driving) "L ${Car.left}   R ${Car.right}" else
            if (Car.online) "Ready" else "Join JVin-Bot Wi-Fi"
    }

    private fun openWifi() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
    }
}

class SettingsActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = vbox().apply {
            setBackgroundColor(C.BG)
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(body)
        })
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(text("Settings", 20f, bold = true))

        val sens = Prefs.sensIdx(this)
        body.addView(tile("Tilt sensitivity: ${Prefs.RANGE_NAMES[sens]}", "Higher means less tilt for full speed", C.CYAN) {
            Prefs.setSensIdx(this, (sens + 1) % Prefs.RANGES.size)
            render()
        })
        val f = Prefs.invFwd(this)
        body.addView(tile("Flip forward/back: ${if (f) "On" else "Off"}", "Use if tilting forward drives backward", if (f) C.ORANGE else C.CARD2) {
            Prefs.setInvFwd(this, !f)
            render()
        })
        val t = Prefs.invTurn(this)
        body.addView(tile("Flip steering: ${if (t) "On" else "Off"}", "Use if tilting right turns left", if (t) C.ORANGE else C.CARD2) {
            Prefs.setInvTurn(this, !t)
            render()
        })
        body.addView(tile("Join car Wi-Fi", "Network JVin-Bot, password vintech254. Stay connected if Android says no internet.", C.BLUE) {
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
        })
        body.addView(text(
            "Safety: the car stops by itself 0.4 s after commands stop. Release the button or the pad to stop.",
            12f, C.SUB
        ).apply { setPadding(0, dp(14), 0, 0) })
    }
}
