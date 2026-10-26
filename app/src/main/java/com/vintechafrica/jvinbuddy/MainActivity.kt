package com.vintechafrica.jvinbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * JVin Buddy.
 *  - Say "hey vinbot", then a command ("sit", "dance", "sing") or any question.
 *    "stop" always works with no wake word.
 *  - Setting "Wake word needed" off = commands work any time, no wake word.
 *  - Hand signs (offline): finger gun, wave, stop palm, thumbs, peace, point, fist, love.
 *  - Tap the face = pet it. Double tap = ask a question. Long press = settings.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var link: RobotLink
    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var tracker: FaceTracker
    private lateinit var senses: Senses
    private lateinit var ai: AiClient
    private lateinit var ears: Ears
    private var hands: HandGestures? = null

    /** After a wake word with no internet: offline commands accepted until this time. */
    private var armedUntil = 0L
    /** What it heard last, shown at the bottom so you can see how well it hears. */
    private val lastHeard = kotlinx.coroutines.flow.MutableStateFlow("")

    private var cameraStarted = false
    private var offlineHintGiven = false

    private val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    private val permissions: Array<String> =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + bluetoothPermissions

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startWhatWeCan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = Prefs(this)
        link = RobotLink(this, prefs, lifecycleScope)
        voice = Voice(
            this,
            onHeard = ::onHeard,
            onListenEnd = {
                brain.paused = false
                ears.start()
            },
            onListenError = ::onListenError,
        )
        ears = Ears(
            this,
            onPhrase = ::onPhrase,
            onUrgentStop = { brain.urgentStop() },
            onCaptured = ::onCaptured,
            onClap = { brain.onClap() },
        )
        brain = Brain(
            link, prefs, lifecycleScope,
            speak = { text, emotion -> voice.say(text, emotion) },
            sing = { notes -> voice.sing(notes) },
        )
        ai = AiClient(prefs)
        hands = try {
            HandGestures(
                this, prefs,
                onSign = { sign -> runOnUiThread { brain.onGesture(sign) } },
                onHand = { x, y -> runOnUiThread { brain.onHand(x, y) } },
            )
        } catch (e: Throwable) {
            null
        }
        tracker = FaceTracker(onFace = { brain.onFace(it) }, hands = hands, needClassify = { brain.isStaring() })
        brain.games = Games(brain, hands, prefs, lifecycleScope)
        senses = Senses(
            this,
            onBump = { brain.onBump() },
            onShake = { brain.onShaken() },
            onLift = { up -> brain.onLifted(up) },
            isDriving = { brain.isDriving },
        )

        brain.start()
        lifecycleScope.launch { link.battery.collect { brain.onBattery(it) } }
        // Do not hear our own voice (plus a short tail so the echo dies out).
        lifecycleScope.launch {
            voice.speaking.collect { talking ->
                if (talking) ears.setPause(true)
                else {
                    delay(700)
                    if (!voice.speaking.value) ears.setPause(false)
                }
            }
        }

        setBrightness(prefs.brightness)
        askToSkipBatteryLimits()

        val missing = permissions.filter { !granted(it) }
        if (missing.isEmpty()) startWhatWeCan() else permLauncher.launch(missing.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Screen() }
        }
    }

    /** Window brightness only (not the phone's system setting). */
    private fun setBrightness(level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.05f, 1f)
        window.attributes = lp
    }

    /** Samsung and Tecno throttle or kill background-heavy apps. Ask once to be left alone. */
    private fun askToSkipBatteryLimits() {
        if (prefs.getInt("asked_battery", 0) == 1) return
        prefs.putInt("asked_battery", 1)
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startWhatWeCan() {
        if (!cameraStarted && granted(Manifest.permission.CAMERA)) {
            cameraStarted = true
            tracker.start(this, this)
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            ears.load()
            ears.start()
        }
        if (bluetoothPermissions.all { granted(it) }) {
            link.start()
        } else {
            link.status.value = "bluetooth permission needed"
        }
    }

    /** Let go of the body when the app is in the background, so another phone can take it. */
    override fun onStop() {
        super.onStop()
        link.shutdown()
    }

    override fun onStart() {
        super.onStart()
        if (::link.isInitialized && bluetoothPermissions.all { granted(it) }) link.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        senses.start()
        if (granted(Manifest.permission.RECORD_AUDIO)) ears.start()
    }

    override fun onPause() {
        super.onPause()
        senses.stop()
        brain.needs.save()
        ears.stop()
        link.stop()
    }

    override fun onDestroy() {
        brain.games.stop()
        link.shutdown()
        voice.shutdown()
        ears.shutdown()
        tracker.shutdown()
        hands?.shutdown()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- talking
    /** Heard by the always-on offline ears. */
    private fun onPhrase(text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        when {
            Commands.isWake(text) -> askQuestion()
            !prefs.wakeRequired || Commands.isAlways(text) || now < armedUntil -> {
                if (brain.handleCommand(text)) armedUntil = 0L
            }
            // Wake word needed and not said: ignore.
        }
    }

    /** Wake word or double tap: record the next sentence with our own mic. No Google, no beeps. */
    private fun askQuestion() {
        brain.paused = true
        brain.listeningForQuestion()
        link.stop()
        ears.capture()
    }

    /** The recorded sentence. Commands are checked offline first; the rest goes to the AI. */
    private fun onCaptured(pcm: ByteArray) {
        if (pcm.isEmpty()) {
            brain.paused = false
            brain.setEmotion(Emotion.NEUTRAL, 0)
            return
        }
        lifecycleScope.launch {
            // 1. Offline guess: instant, and enough for commands like "dance" or "sit"
            val local = withContext(Dispatchers.Default) { ears.transcribe(pcm) }
            if (local.isNotBlank() && brain.handleCommand(local)) {
                brain.paused = false
                return@launch
            }
            // 2. Online ears hear full sentences and accents much better
            val heard = if (Net.online(this@MainActivity)) {
                brain.thinking()
                ai.transcribe(pcm) ?: local
            } else local
            brain.paused = false
            if (heard.isBlank()) {
                brain.react(Emotion.CURIOUS, "Sorry, I didn't catch that.", 2500)
                return@launch
            }
            onHeard(heard)
        }
    }

    private fun onHeard(text: String) {
        lastHeard.value = text
        // 0. "sing about the rain": the AI writes an original song, the robot sings it.
        val topic = Regex("^sing (?:a song |me a song )?(?:about|for) (.+)").find(Commands.normalize(text))?.groupValues?.get(1)
        if (topic != null && Net.online(this) && ai.mode != "none") {
            brain.thinking()
            lifecycleScope.launch {
                val (_, lyrics) = ai.ask(
                    "Write an original, cheerful song about $topic. Exactly 4 short lines, " +
                        "6 to 8 words each, one line per sentence, ending each with a full stop. Lyrics only."
                )
                brain.singSong(Songs.fromLyrics(lyrics))
            }
            return
        }

        // 1. Commands: always local, work offline.
        if (brain.handleCommand(text)) return

        // 2. Simple answers: local and instant.
        val online = Net.online(this)
        LocalReplies.answer(text, link.battery.value, online)?.let { (emotion, reply) ->
            brain.answered(emotion, reply)
            return
        }

        // 3. Everything else needs the AI.
        if (!online) {
            val (emotion, reply) = LocalReplies.offline()
            brain.answered(emotion, reply)
            return
        }
        brain.thinking()
        lifecycleScope.launch {
            val (emotion, reply) = ai.ask(text)
            brain.answered(emotion, reply)
        }
    }

    private fun onListenError(error: Int) {
        val online = Net.online(this)
        when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                if (!online) {
                    // No internet: let the offline ears take a command instead.
                    armedUntil = android.os.SystemClock.elapsedRealtime() + 7000
                    voice.say(
                        if (offlineHintGiven) "I'm offline. Say a command." else
                            "I'm offline, so just say a command like sit or dance.",
                        Emotion.CURIOUS,
                    )
                    offlineHintGiven = true
                } else {
                    voice.say("My ears are not working right now.", Emotion.SAD)
                }
            }
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                brain.setEmotion(Emotion.CURIOUS, 2000)
                voice.say("Sorry, I didn't catch that.", Emotion.CURIOUS)
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- UI
    @Composable
    private fun Screen() {
        val emotion by brain.emotion.collectAsState()
        val look by brain.lookX.collectAsState()
        val lookY by brain.lookY.collectAsState()
        val winkAt by brain.winkAt.collectAsState()
        val follow by brain.follow.collectAsState()
        val listening by ears.capturing.collectAsState()
        val heardText by lastHeard.collectAsState()
        val speaking by voice.speaking.collectAsState()
        val linkStatus by link.status.collectAsState()
        val battery by link.battery.collectAsState()
        val manual by link.manual.collectAsState()
        val earsStatus by ears.status.collectAsState()
        val brainError by ai.lastError.collectAsState()
        val overlay by brain.overlay.collectAsState()
        val noBlink by brain.noBlink.collectAsState()
        var showSettings by remember { mutableStateOf(false) }
        var fps by remember { mutableFloatStateOf(0f) }
        var online by remember { mutableStateOf(false) }
        var statusUntil by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime() + 30_000) }
        var nowTick by remember { mutableStateOf(0L) }
        var errorSince by remember { mutableStateOf(0L) }

        // Burn-in guard: the whole face drifts a few pixels every 2 minutes.
        var shiftX by remember { mutableStateOf(0) }
        var shiftY by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(120_000)
                shiftX = (-10..10).random()
                shiftY = (-8..8).random()
            }
        }
        // Dim the screen while it sleeps
        LaunchedEffect(emotion) {
            setBrightness(if (emotion == Emotion.SLEEPY) 0.08f else prefs.brightness)
        }
        LaunchedEffect(brainError) { errorSince = android.os.SystemClock.elapsedRealtime() }

        LaunchedEffect(Unit) {
            while (true) {
                fps = tracker.fps
                online = Net.online(this@MainActivity)
                nowTick = android.os.SystemClock.elapsedRealtime()
                delay(1000)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { brain.pet() },
                        onDoubleTap = { askQuestion() },
                        onLongPress = { showSettings = true },
                    )
                }
        ) {
            RobotFace(
                emotion = emotion, lookX = look, lookY = lookY, winkAt = winkAt,
                listening = listening, speaking = speaking, noBlink = noBlink,
                modifier = Modifier.offset(x = shiftX.dp, y = shiftY.dp),
            )

            // Game overlay: hands, scores
            overlay?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = if (text.length <= 3) 96.sp else 40.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .background(Color(0xAA000000))
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            // Status text never moves, so it burns in first: show it briefly, then hide.
            val showText = prefs.showStatus || nowTick < statusUntil
            // Errors show for 20 s, then hide
            if (brainError.isNotBlank() && nowTick - errorSince < 20_000 && !showText) {
                Text(
                    text = brainError,
                    color = Color(0xCCFF8A80),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
            if (showText) {
                val parts = buildList {
                    add("v${BuildConfig.VERSION_NAME}")
                    add(if (manual) "MANUAL CONTROL" else linkStatus)
                    battery?.let { add("%.2f V".format(it)) }
                    add(if (online) "online" else "offline")
                    add(earsStatus)
                    add("brain ${ai.mode}")
                    add("eyes %.1f fps".format(fps))
                    add(
                        when {
                            hands?.loaded != true -> "hands off (no model)"
                            prefs.gestures -> "hands on"
                            else -> "hands off"
                        }
                    )
                    add("speed ${prefs.speedPercent}%")
                    val n = brain.needs
                    add("social %.0f fun %.0f energy %.0f".format(n.social, n.fun_, n.energy))
                    add("follow ${if (follow) "on" else "off"}")
                    add(emotion.name.lowercase())
                }
                if (heardText.isNotBlank()) {
                    Text(
                        text = "heard: \"$heardText\"",
                        color = Color(0x99B2EBF2),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    )
                }
                if (brainError.isNotBlank()) {
                    Text(
                        text = brainError,
                        color = Color(0xCCFF8A80),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
                Text(
                    text = parts.joinToString(" | "),
                    color = Color(0x88FFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }

        if (showSettings) {
            SettingsDialog(
                onDismiss = { showSettings = false },
                onSaved = {
                    statusUntil = android.os.SystemClock.elapsedRealtime() + 30_000
                    showSettings = false
                    recreate()
                },
            )
        }
    }

    @Composable
    private fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
        var url by remember { mutableStateOf(prefs.workerUrl) }
        var token by remember { mutableStateOf(prefs.token) }
        var key by remember { mutableStateOf(prefs.geminiKey) }
        var groq by remember { mutableStateOf(prefs.groqKey) }
        var low by remember { mutableStateOf(prefs.lowBattery.toString()) }
        var invert by remember { mutableStateOf(prefs.invertSteer) }
        var status by remember { mutableStateOf(prefs.showStatus) }
        var bright by remember { mutableStateOf(prefs.brightness) }
        var speed by remember { mutableIntStateOf(prefs.speedPercent) }
        var gestures by remember { mutableStateOf(prefs.gestures) }
        var wake by remember { mutableStateOf(prefs.wakeRequired) }
        var autoMove by remember { mutableStateOf(prefs.autoMove) }
        var birthday by remember { mutableStateOf(prefs.birthday) }
        var forget by remember { mutableStateOf(false) }
        val paired = prefs.robotAddress.ifBlank { "none yet" }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("JVin Buddy settings") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(groq, { groq = it }, label = { Text("Groq API key (free, recommended)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("Gemini API key (backup)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Text("Or use your Cloudflare Worker instead:", fontSize = 12.sp)
                    OutlinedTextField(url, { url = it }, label = { Text("Worker URL (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Robot token") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(low, { low = it }, label = { Text("Low battery volts") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(birthday, { birthday = it }, label = { Text("Your birthday (MM-dd, e.g. 03-15)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Motor speed: $speed%")
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { speed = it.toInt() },
                        valueRange = 25f..100f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(gestures, { gestures = it }); Text("  Hand gesture tricks")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(wake, { wake = it }); Text("  Wake word needed for commands (off = faster)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(autoMove, { autoMove = it }); Text("  Moves on its own (follows, wanders)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(invert, { invert = it }); Text("  Invert steering")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(status, { status = it }); Text("  Always show status line (burn-in risk)")
                    }
                    Text("Screen brightness: ${(bright * 100).toInt()}%")
                    Slider(value = bright, onValueChange = { bright = it }, valueRange = 0.1f..1f)
                    Text("Paired body: $paired", fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(forget, { forget = it }); Text("  Forget body and scan again")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.workerUrl = url
                    prefs.token = token
                    prefs.geminiKey = key
                    prefs.groqKey = groq
                    low.toFloatOrNull()?.let { prefs.lowBattery = it }
                    prefs.invertSteer = invert
                    prefs.showStatus = status
                    prefs.brightness = bright
                    prefs.speedPercent = speed
                    prefs.gestures = gestures
                    prefs.wakeRequired = wake
                    prefs.autoMove = autoMove
                    if (birthday.isBlank() || Regex("\\d{2}-\\d{2}").matches(birthday.trim())) prefs.birthday = birthday
                    if (forget) prefs.robotAddress = ""
                    onSaved()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
