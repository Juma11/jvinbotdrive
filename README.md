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
5. Long press the face > settings > paste a **Groq API key** (free from console.groq.com, no card). That is all the brain needs. A Gemini key is the backup. The Worker is optional.
6. No Google speech app needed any more. VinBot uses its own microphone: commands are understood offline, questions go to Groq Whisper (or Gemini) as audio.
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
| "hey vinbot" questions and web research | Yes | No, it says it is offline |

## Use

No button pressing. Say **"hey vinbot"** first, then the command or question.

- **"hey vinbot" ... "sit" / "dance" / "sing" / any question.** Online, it hears the whole sentence. Offline, it says "Yes?" and takes one command in the next 7 seconds.
- **"stop" and "freeze" always work**, no wake word, so you can always stop it.
- **Setting "Wake word needed" off:** commands work any time without "hey vinbot" (more fun, more false triggers).
- **Tap face:** pet it (it winks). **Double tap:** ask a question. **Long press:** settings.

"VinBot" is not an English word, so the offline ears match it to sound-alikes ("hey vin bot", "hey ben bot", "hey bin bot"...). Say it clearly as two parts: "hey VIN-BOT". "hey robot" also works.

**Reacts to people (no commands needed)**

| You do | It does |
|---|---|
| Come back after a while | Greets you by time of day ("Good morning Vincent!"), or "I missed you!" after 5 min |
| Walk away after playing | "Where did you go?", sad face, looks around for you |
| Stare at it for about 12 s | Gets shy, blushes, "Why are you staring at me?" |
| Put your face very close | Scared face, "Too close!", backs off |
| Bring a friend (2 faces) | "Ooh, who's your friend?" |
| Tap the face | Wink and giggle (petting) |
| Tap 3 times fast | Laughs, "That tickles!" |
| Pick it up | Scared, "Put me down!", motors locked until it is back down |
| Put it down | "Phew, solid ground." |
| Shake it | Dizzy spiral eyes, "Stop shaking me!" |
| Ignore it | Bored, then asks "Play with me!", then sleeps (faster after 10 pm) |

**Emotion and trick commands (after "hey vinbot")**

be angry, be scared, boo, be happy, smile, be sad, cry, laugh, say sorry, i love you, give me a kiss, celebrate, party, look around, do a trick (random trick).

Face expressions: neutral, happy, curious, bored, sleepy, surprised, sad, tired, thinking, angry (red, slanted lids, steam), scared (trembling, sweat drop), laugh (bouncing grin), shy (blush, looks away), excited (star eyes, sparkles), crying (tear streams), love (heart eyes), dizzy (spirals), dead (X eyes), singing (notes). The AI can pick any of these for its answers.

**Games (after "hey vinbot")**

| Say | Game |
|---|---|
| rock paper scissors | Best of three with real hand shapes. It counts "rock, paper, scissors, shoot!", shows the hand it picked, and keeps score. |
| staring contest | It stops blinking and watches your eyes. Blink or look away and you lose. It gives up after 10 to 25 s. |
| peekaboo | Hide your face with your hands; it worries. Show it; it laughs. 4 rounds. |
| play a game | Picks one of the three |
| again / rematch | Plays the last game again |
| stop the game | Ends the game |

Peekaboo also works without asking: cover your face with your hands any time and it reacts.

**Personality that grows**

- **Praise learning:** say "good boy", give a thumbs up or pat it within 10 s of a trick, and it does that trick more often. Say "bad robot" or thumbs down and it does it less. "Do a trick" picks from what you like.
- **Needs:** social, fun and energy (shown on the status line). Ignored, it gets lonely and asks for attention. No games, it gets bored and asks to play. Too many tricks, it gets tired and naps; pat it to wake it.
- **Chattier with time:** a new VinBot is quiet. The more you play, the more it talks on its own.
- **Milestones:** at 50, 100, 250, 500 and 1000 interactions it celebrates your friendship.
- **"How do you feel?"** It answers from its needs.
- **Smile mirroring:** smile at it and it smiles back and winks.
- **Daily surprises:** 16 rare things it does on its own (hiccups, sneezing fits, chasing a bug, practising a dance and getting shy when caught, beatboxing, daydreaming, "Jambo! Hakuna matata!"...). Each waits 3 days before repeating.
- **Special days:** Kenyan holidays (New Year, Labour Day, Madaraka, Mazingira, Mashujaa, Jamhuri, Christmas, Boxing Day) and your birthday. Set your birthday in settings as MM-dd; it sings Happy Birthday when it first sees you that day.

All of this is saved on the phone, so it remembers between days. Uninstalling resets it.

**Singing**

| Say (after "hey vinbot") | It sings |
|---|---|
| sing, sing a song, sing for me | A random song: Twinkle Twinkle or one of its own robot songs |
| sing happy birthday / sing happy birthday to Brian | Happy Birthday, with the name (name needs internet) |
| sing twinkle twinkle | Twinkle Twinkle Little Star |
| sing about the rain (any topic, online) | The AI writes an original 4-line song and it sings it |

While singing it sways side to side, its eyes squint happily and music notes float up. It is text-to-speech with a pitch per syllable, so it sounds like a robot singing, with small gaps between syllables on the Spark 4. Add songs in `Songs.kt`. Use only public domain songs or your own lyrics.

**Hand signs (offline, no talking needed)**

Stand 0.5 to 2 m in front of the phone, in good light, palm toward the camera. Hold a sign for half a second.

| Sign | It does |
|---|---|
| Finger gun (index out, thumb up) | Plays dead: X eyes, falls back, revives after 5 s |
| Wave (open palm side to side) | "Hi!" and wiggle |
| Open palm held still (1 s) | Stops, like telling a dog to stay |
| Thumbs up | Happy "Thanks!" |
| Thumbs down | Sad, looks away |
| Peace sign | Dances |
| Index finger up | Spins |
| Fist | "Fist bump!" hop |
| Rock sign (thumb, index, pinky) | Heart eyes, "I love you too!" |
| Point left or right | Turns that way |

The same sign fires once. Drop your hand or change the sign to trigger it again.
If pointing turns the wrong way, the face-follow steering is also wrong: fix both with **Invert steering**.

**Speed:** long press > Motor speed (default 55%). Tricks run longer at low speed so turns keep their size. If 25% is still too fast, lower `MIN_PWM` in the firmware (motors may stall below about 70).

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

**Local answers (with "hey vinbot"):** time, date, your name, who made you, how are you, what can you do, battery, hello, thanks, jokes (offline).

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
| Singing | "sing" | Sways, music notes, happy eyes |
| Dizzy | After spin or dance | Spiral eyes, stars |
| Love | Rock hand sign | Heart eyes, floating hearts |
| Dead | "bang", "shoot", "play dead" | Falls back, X eyes, revives |

## How it hears (v9)

1. The offline ears listen only for the wake word, "stop", and the command list. Low-confidence matches are ignored, so noise does not wake it.
2. After "hey vinbot" (or a double tap) it records your sentence until you stop talking (max 7 s). Red bars show it is recording.
3. Offline it checks the recording for a command first ("dance", "sit"...). That is instant and free.
4. Anything else goes online: Groq Whisper writes down what you said, then the AI answers. The bottom right shows `heard: "..."` so you can see how well it heard you.
5. A clap or sudden loud sound makes it look around: "You called?"

Quota guard: at most 3 AI requests per question, 2 s between questions, 300 per day.

## Movement rules (v9.1)

- By default the wheels only move within 15 s of a command, a hand sign, or during a game. Without one, it still reacts with its face and voice, but stays put.
- "follow me" / "come here" turns following on until you say "stop".
- Settings > "Moves on its own" brings back free following and wandering.
- Settings > "Wake word needed for commands": switch OFF for instant commands (about half a second). Questions still need "hey vinbot".
- The status line starts with the version, e.g. `v9.14` = GitHub build 14. If it does not match your latest build, the new build failed or did not install.

## Galaxy A32 and other AMOLED phones (v9.2)

- 8-core phones are detected automatically: bigger camera frames, more hand checks, more smile/eye checks.
- Burn-in guard: the status line shows for 30 s after start or after saving settings, then hides. Errors show for 20 s. The whole face drifts a few pixels every 2 minutes. The screen dims while it sleeps.
- Settings > Screen brightness (default 55%). Keep it at 60% or lower on AMOLED.
- On first start it asks to ignore battery optimisation. Say Allow, or Samsung will throttle it.
- Keys are stored per phone: paste your Groq key again on the new phone.
