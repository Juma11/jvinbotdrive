package com.vintechafrica.jvinbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/**
 * JVin Buddy. Tap the face to talk. Long press for settings.
 * Works online and offline. Offline it still follows, emotes, obeys commands
 * and answers simple things like the time.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var link: RobotLink
    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var tracker: FaceTracker
    private lateinit var senses: Senses
    private lateinit var ai: AiClient

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
        hideSystemBars()

        prefs = Prefs(this)
        link = RobotLink(this, prefs, lifecycleScope)
        voice = Voice(
            this,
            onHeard = ::onHeard,
            onListenEnd = { brain.paused = false },
            onListenError = ::onListenError,
        )
        brain = Brain(link, prefs, lifecycleScope) { text, emotion -> voice.say(text, emotion) }
        ai = AiClient(prefs)
        tracker = FaceTracker { brain.onFace(it) }
        senses = Senses(this) { brain.onBump() }

        brain.start()
        lifecycleScope.launch { link.battery.collect { brain.onBattery(it) } }

        val missing = permissions.filter { !granted(it) }
        if (missing.isEmpty()) startWhatWeCan() else permLauncher.launch(missing.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Screen() }
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startWhatWeCan() {
        if (!cameraStarted && granted(Manifest.permission.CAMERA)) {
            cameraStarted = true
            tracker.start(this, this)
        }
        if (bluetoothPermissions.all { granted(it) }) {
            link.start()
        } else {
            link.status.value = "bluetooth permission needed"
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        senses.start()
    }

    override fun onPause() {
        super.onPause()
        senses.stop()
        link.stop()
    }

    override fun onDestroy() {
        link.shutdown()
        voice.shutdown()
        tracker.shutdown()
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
    private fun onTapFace() {
        if (brain.onTap()) return
        if (!voice.available) {
            voice.say("I can't hear. Install or update the Google app.", Emotion.SAD)
            return
        }
        brain.paused = true
        link.stop()
        voice.listen(preferOffline = !Net.online(this))
    }

    private fun onHeard(text: String) {
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
                if (!online && !offlineHintGiven) {
                    offlineHintGiven = true
                    voice.say(
                        "I can't hear without internet yet. Download offline English in the Google app voice settings.",
                        Emotion.SAD,
                    )
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
        val follow by brain.follow.collectAsState()
        val listening by voice.listening.collectAsState()
        val speaking by voice.speaking.collectAsState()
        val linkStatus by link.status.collectAsState()
        val battery by link.battery.collectAsState()
        val manual by link.manual.collectAsState()
        var showSettings by remember { mutableStateOf(false) }
        var fps by remember { mutableFloatStateOf(0f) }
        var online by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                fps = tracker.fps
                online = Net.online(this@MainActivity)
                delay(1000)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTapFace() },
                        onLongPress = { showSettings = true },
                    )
                }
        ) {
            RobotFace(emotion = emotion, lookX = look, listening = listening, speaking = speaking)

            if (prefs.showStatus) {
                val parts = buildList {
                    add(if (manual) "MANUAL CONTROL" else linkStatus)
                    battery?.let { add("%.2f V".format(it)) }
                    add(if (online) "online" else "offline")
                    add("face %.1f fps".format(fps))
                    add("follow ${if (follow) "on" else "off"}")
                    add(emotion.name.lowercase())
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
        var low by remember { mutableStateOf(prefs.lowBattery.toString()) }
        var invert by remember { mutableStateOf(prefs.invertSteer) }
        var status by remember { mutableStateOf(prefs.showStatus) }
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
                    OutlinedTextField(url, { url = it }, label = { Text("Worker URL (https://...)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Robot token") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(low, { low = it }, label = { Text("Low battery volts") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(invert, { invert = it }); Text("  Invert steering")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(status, { status = it }); Text("  Show status line")
                    }
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
                    low.toFloatOrNull()?.let { prefs.lowBattery = it }
                    prefs.invertSteer = invert
                    prefs.showStatus = status
                    if (forget) prefs.robotAddress = ""
                    onSaved()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
