# JVin Buddy

Phone brain on the JVin bot. The Tecno Spark 4 is the face, eyes, ears and mouth. The ESP32 only drives motors.

```
firmware/JVinBuddy_ESP32/   ESP32 body (BLE brain link, WiFi AP manual controller, failsafe)
worker/                     Cloudflare Worker brain (AI + web search)
app/                        Android app (face, follow, emotions, voice, offline mode)
.github/workflows/          Builds the APK on every push
```

## How it connects

```
Spark 4 ──BLE──> ESP32 ──> motors          (always, no internet needed)
Spark 4 ──home WiFi or mobile data──> Worker ──> AI   (only for questions)
Your phone ──WiFi "JVin-Bot"──> ESP32      (manual controller)
```

- The Spark 4 talks to the body over Bluetooth, so its WiFi stays free for internet.
- Manual control always wins. Drive from the AP page and the brain is locked out until 1.5 s after your last press.
- Status line shows `MANUAL CONTROL` on the face while you drive.

## 1. ESP32 body

1. Arduino IDE: ESP32 board package **3.x**, library **WebSockets** by Markus Sattler.
2. **Tools > Partition Scheme > Huge APP (3MB No OTA/1MB SPIFFS).** WiFi plus Bluetooth does not fit the default partition, and the upload fails without this.
3. Flash. Serial monitor (115200) prints `BLE advertising as JVinBuddy`.
4. Test manual control: join WiFi `JVin-Bot` / `vintech254`, open `http://192.168.4.1`, drive with the arrows.
5. Test BLE without the app: nRF Connect or Serial Bluetooth Terminal, connect to `JVinBuddy`, write `D,180,180` repeatedly. The wheels run, then stop 0.4 s after you stop sending.

Pins are your JVin-Bot v2 wiring. Unplug the wires on GPIO 5 and 15 if an upload fails.

Battery reading is off (`BATT_PIN -1`). To turn it on, wire a divider from the battery to GPIO 34 (for a 2S pack: 20k to battery, 10k to GND, ratio 3.0) and set `BATT_PIN 34`.

## 2. Worker brain (optional)

Skip this if you use a Gemini key in the app. Use the Worker only if other people will install your app, so they never see your key.

```
cd worker
npx wrangler login
npx wrangler secret put ROBOT_TOKEN       # make up a long random string
npx wrangler secret put GEMINI_API_KEY    # from aistudio.google.com
npx wrangler deploy
```

Test it:

```
curl -X POST https://jvin-brain.<you>.workers.dev -H "X-Robot-Token: <token>" \
  -H "content-type: application/json" -d '{"text":"what is the weather in Nairobi"}'
```

To use Claude instead: set `PROVIDER = "claude"` and a Claude `MODEL` in `wrangler.toml`, then `npx wrangler secret put ANTHROPIC_API_KEY`.

Check the provider's current free limits for search grounding before you leave it running.

## 3. App

1. Push this folder to `Juma11/jvinbotdrive` (or a new repo).
2. GitHub > Actions > Build APK > download `jvinbuddy-debug-apk`.
3. Install on the Spark 4. Allow camera, microphone and location. Android 10 needs location for the first Bluetooth scan only.
4. Turn on Bluetooth and Location for the first run. After the body pairs, location can go off.
5. Long press the face > settings > paste a **Gemini API key** (free from aistudio.google.com). That is all the brain needs. The Worker is optional.
6. Google app > Settings > Voice > Offline speech recognition > download English. Needed only for questions while offline. Commands are always offline.
7. Tecno battery saver kills background apps and dims the screen. Phone Master > Battery > App launch / power saving: set JVin Buddy to **manual, all allowed**. Settings > Display: sleep timeout to the maximum as a backup.

Fixed download link for the newest build (after the first push with this workflow):
`https://github.com/Juma11/jvinbotdrive/releases/latest/download/jvinbuddy.apk`

The repo has a fixed debug key, so each new build installs over the old one.

## 4. First run checklist

1. Status line shows `body ok`. If it stays on `searching for body`, check Bluetooth and Location are on.
2. Wrong board paired? Settings > Forget body and scan again.
3. Status shows `face ... fps`. Under 3 fps means following will be poor.
4. Stand to one side. The eyes must look toward you and the robot must turn toward you. If both go the wrong way, turn on **Invert steering**.
5. If one side of the car runs backwards, flip `INVERT_LEFT` or `INVERT_RIGHT` in the firmware.
6. Tune follow speed in `Brain.kt`: `turnGain`, `forwardSpeed`, `farSize`, `nearSize`.
7. Turn off WiFi and mobile data on the Spark 4. Status shows `offline`. Ask "what time is it" and say "follow me". Both must still work.

## Online vs offline

| Feature | Online | Offline |
|---|---|---|
| Face, emotions, following, bump reactions | Yes | Yes |
| Driving the body (BLE) | Yes | Yes |
| Manual controller (AP) | Yes | Yes |
| Voice commands (always listening) | Yes | Yes |
| Time, date, name, greetings, battery, jokes | Yes | Yes |
| "hey buddy" questions and web research | Yes | No, it says it is offline |

## Use

No button pressing. The robot always listens for commands, fully offline.

- **Talk to it:** say **"hey buddy"**, wait for the curious face and red dot, then ask anything.
- **Tap face:** pet it. **Double tap:** ask a question. **Long press:** settings.

**Commands (always on, offline)**

| Say | It does |
|---|---|
| bang, bang bang, shoot, play dead | X eyes, falls back, "You got me!", revives after 5 s |
| stop, freeze, sit, stay, wait | Stops, stops following |
| come, come here, come on, here boy, follow me | Follows you |
| look at me, watch me | Turns to face you |
| go round, go around, turn around | Half turn |
| spin, roll over | Full spin |
| go back, move back, back up, reverse | Reverses |
| go forward, forward, go ahead | Drives forward |
| turn left, go left, turn right, go right | Turns |
| dance | Dance routine |
| speak, bark | "Woof! Woof!" |
| shake, high five, say hello | Wiggle and hello |
| jump | Hop |
| good boy, good dog, good robot, well done | Happy wiggle |
| bad dog, bad robot, no | Sad, looks away |
| go away, shut up | Turns away, stops following |
| go to sleep, sleep, lie down / wake up | Sleeps / wakes |

**On its own:** looks around, peeks left and right, sneezes, yawns and hums when bored, greets you when you come back.

To add a command: add the phrase to `Commands.kt`, then handle it in `Brain.handleCommand`. Use real English words only.

**Local answers (with "hey buddy"):** time, date, your name, who made you, how are you, what can you do, battery, hello, thanks, jokes (offline).

## Emotions

| Emotion | Trigger | Movement |
|---|---|---|
| Happy | Face seen, praise, AI reply | Wiggle |
| Curious | Face appears after 10 s alone, misheard speech | Then happy |
| Bored | No face for 30 s | Still |
| Sleepy | No face for 2 min, or "go to sleep" | Still, no following |
| Surprised | Bump or lift (phone accelerometer) | Backs off |
| Sad | "bad robot", "go away", offline question | Turns away |
| Tired | Battery below threshold | Asks to charge |
| Thinking | Waiting for the AI | Small head shake |
| Dead | "bang", "shoot", "play dead" | Falls back, X eyes, revives |
