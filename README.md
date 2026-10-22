# JVin Drive

Android controller for the JVin-Bot ESP32 4WD car. Runs on a phone and on the
HainoTeko FG-3 watch (Android 8.1).

- **Tilt:** hold the round button, tilt to drive, release to stop. Your pose at the
  press becomes neutral. Uses only the accelerometer, so no gyro needed.
- **Pad:** on-screen joystick. Default on phones.
- Speed limit 40 / 70 / 100 %, live link status with round-trip time.
- Binds to the car's Wi-Fi directly, so a device with mobile data still reaches the car.

## Car

`firmware/JVinBot_v3/JVinBot_v3.ino`, ESP32 Arduino core 3.x, no extra libraries.

| | |
|---|---|
| Wi-Fi | `JVin-Bot` / `vintech254` |
| Page | http://192.168.4.1 (phone web joystick) |
| `GET /joy?l=&r=` | left, right speed, -255 to 255 |
| `GET /stop` | stop |
| `GET /ping` | returns OK |

Failsafe: the car stops 400 ms after the last `/joy`. The app sends every 80 ms while driving.

## Build

GitHub Actions builds the APK on every push to `main`, checks the firmware compiles,
and publishes both to the `latest` release.

## Test order

1. Flash the firmware. Drive with the web page. Turn the phone's Wi-Fi off mid-drive:
   the car must stop within half a second.
2. Phone app, Pad mode, then Tilt mode. Flip directions in Settings if reversed.
3. Watch, on a table, a few minutes at a time. The FG-3 battery runs hot.
