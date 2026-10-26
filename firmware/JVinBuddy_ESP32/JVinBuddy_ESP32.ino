/*
 * JVin Buddy, ESP32 body firmware v2 (Vintech Africa)
 * The phone is the brain. This board only drives motors and reports battery.
 *
 * Two control paths, both always on:
 *   1. BLE (phone brain). Nordic UART service, device name "JVinBuddy".
 *      Phone writes:  D,<left>,<right>  (-255..255)   S (stop)   P (ping)
 *      Board notifies: B,<volts> every 2 s (if BATT_PIN set)
 *                      M,1 / M,0 when manual control takes over / releases
 *   2. WiFi AP (manual controller). SSID JVin-Bot, pass vintech254.
 *      Open http://192.168.4.1 and drive with the arrows.
 *
 * Priority: manual wins. Any manual movement locks out the phone brain for
 * 1.5 s after your last press, then the brain gets control back.
 *
 * Failsafe: motors stop if no applied command for 400 ms.
 *
 * Arduino IDE settings:
 *   Board package: ESP32 core 3.x
 *   Library: "WebSockets" by Markus Sattler (Links2004)
 *   Tools > Partition Scheme > "Huge APP (3MB No OTA/1MB SPIFFS)"
 *   (WiFi + BLE does not fit the default partition.)
 *
 * Pins match your JVin-Bot v2 wiring (2x L298N).
 */

#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ----------------------------------------------------------- pins (front L298N)
#define IN1 25   // front left
#define IN2 23
#define ENA 16
#define IN3 15   // front right
#define IN4 21
#define ENB 17
// ----------------------------------------------------------- pins (back L298N)
#define IN1B 4   // back left
#define IN2B 5
#define ENAB 13
#define IN3B 18  // back right
#define IN4B 19
#define ENBB 22

// Battery sense through a voltage divider on an ADC1 pin. Set to -1 if not wired.
// 2S pack (max 8.4 V): 20k to battery, 10k to GND, ratio 3.0, pin 34.
#define BATT_PIN -1
const float BATT_RATIO = 3.0;

const bool INVERT_LEFT = false;
const bool INVERT_RIGHT = false;
const int MIN_PWM = 90;              // motors stall below this
const uint32_t FAILSAFE_MS = 400;
const uint32_t MANUAL_HOLD_MS = 1500;

const char* AP_SSID = "JVin-Bot";
const char* AP_PASS = "vintech254";

// Nordic UART service, so BLE test apps (nRF Connect, Serial Bluetooth Terminal) understand it.
#define NUS_SERVICE "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define NUS_RX      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // phone writes here
#define NUS_TX      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // board notifies here

WebServer http(80);
WebSocketsServer ws(81);
BLEServer* bleServer = nullptr;
BLECharacteristic* txChar = nullptr;

// Written by the BLE task, read in loop().
volatile int bleL = 0, bleR = 0;
volatile bool bleNew = false;
volatile bool bleConnected = false;
volatile bool bleWasConnected = false;

uint32_t lastApplied = 0;      // last command that actually moved/stopped the motors
uint32_t lastManualMove = 0;   // last nonzero manual command
uint32_t lastBatt = 0;
bool driving = false;
bool manualWasActive = false;

// ----------------------------------------------------------- motors
void sideOut(int inA, int inB, int en, int speed) {
  speed = constrain(speed, -255, 255);
  int pwm = abs(speed);
  if (pwm > 0 && pwm < MIN_PWM) pwm = MIN_PWM;
  if (speed > 0)      { digitalWrite(inA, HIGH); digitalWrite(inB, LOW); }
  else if (speed < 0) { digitalWrite(inA, LOW);  digitalWrite(inB, HIGH); }
  else                { digitalWrite(inA, LOW);  digitalWrite(inB, LOW); }
  analogWrite(en, pwm);
}

void drive(int left, int right) {
  if (INVERT_LEFT) left = -left;
  if (INVERT_RIGHT) right = -right;
  sideOut(IN1, IN2, ENA, left);
  sideOut(IN1B, IN2B, ENAB, left);
  sideOut(IN3, IN4, ENB, right);
  sideOut(IN3B, IN4B, ENBB, right);
  driving = (left != 0 || right != 0);
  lastApplied = millis();
}

void stopAll() { drive(0, 0); }

bool manualActive() { return millis() - lastManualMove < MANUAL_HOLD_MS; }

// Parses "D,l,r" or "S". Returns true if it was a drive/stop command.
bool parseDrive(const String& msg, int& l, int& r) {
  if (msg == "S") { l = 0; r = 0; return true; }
  if (!msg.startsWith("D,")) return false;
  int c2 = msg.indexOf(',', 2);
  if (c2 < 0) return false;
  l = msg.substring(2, c2).toInt();
  r = msg.substring(c2 + 1).toInt();
  return true;
}

// ----------------------------------------------------------- BLE
class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    bleConnected = true;
    bleWasConnected = true;
  }
  void onDisconnect(BLEServer*) override {
    bleConnected = false;
    bleL = 0; bleR = 0; bleNew = true;   // stop
  }
};

class RxCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String msg = String(c->getValue().c_str());
    msg.trim();
    int l, r;
    if (parseDrive(msg, l, r)) {
      bleL = l; bleR = r; bleNew = true;
    }
    // "P" needs no action: the next "D" keeps the failsafe fed.
  }
};

void notifyPhone(const char* text) {
  if (!bleConnected || !txChar) return;
  txChar->setValue((uint8_t*)text, strlen(text));
  txChar->notify();
}

void startBle() {
  BLEDevice::init("JVinBuddy");
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCb());
  BLEService* svc = bleServer->createService(NUS_SERVICE);

  BLECharacteristic* rx = svc->createCharacteristic(
      NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCb());

  txChar = svc->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE advertising as JVinBuddy");
}

// ----------------------------------------------------------- manual controller (WiFi AP)
const char PAGE[] PROGMEM = R"HTML(<!doctype html><html><head>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>JVin Buddy</title>
<style>body{background:#0b1a2e;color:#e6f6ff;font-family:sans-serif;text-align:center;margin:0;padding:16px}
.g{display:grid;grid-template-columns:repeat(3,90px);gap:10px;justify-content:center;margin-top:20px}
button{height:90px;border:0;border-radius:14px;background:#00b8d9;color:#04121f;font-size:28px;user-select:none;touch-action:none}
#stop{background:#ff5252;color:#fff}
#s{margin-top:12px;color:#8fb3c9}
input{width:220px}</style></head><body>
<h2>JVin Buddy manual drive</h2><div id=s>connecting...</div>
<div class=g>
<span></span><button data-l=1 data-r=1>&#9650;</button><span></span>
<button data-l=-1 data-r=1>&#9664;</button><button id=stop data-l=0 data-r=0>&#9632;</button><button data-l=1 data-r=-1>&#9654;</button>
<span></span><button data-l=-1 data-r=-1>&#9660;</button><span></span></div>
<p>Speed <input id=sp type=range min=90 max=255 value=180></p>
<script>
let l=0,r=0,ws,held=false;const s=document.getElementById('s'),sp=document.getElementById('sp');
function go(){ws=new WebSocket('ws://'+location.hostname+':81/');
ws.onopen=()=>s.textContent='connected';ws.onclose=()=>{s.textContent='LINK LOST';setTimeout(go,1000)};
ws.onmessage=e=>{if(e.data.startsWith('B,'))s.textContent='battery '+e.data.slice(2)+' V';
 if(e.data.startsWith('M,'))s.textContent=e.data=='M,1'?'you are driving':'brain has control'}}
go();
document.querySelectorAll('button').forEach(b=>{
 b.onpointerdown=()=>{held=true;const v=+sp.value;l=+b.dataset.l*v;r=+b.dataset.r*v;if(b.id=='stop'&&ws.readyState==1)ws.send('S')};
 b.onpointerup=b.onpointerleave=()=>{l=0;r=0}});
setInterval(()=>{if(ws&&ws.readyState==1&&held)ws.send('D,'+l+','+r)},100);
</script></body></html>)HTML";

void onWs(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
  if (type == WStype_CONNECTED) {
    ws.sendTXT(num, manualActive() ? "M,1" : "M,0");
    return;
  }
  if (type == WStype_DISCONNECTED) {
    if (manualActive()) stopAll();
    return;
  }
  if (type != WStype_TEXT) return;

  String msg;
  msg.reserve(length);
  for (size_t i = 0; i < length; i++) msg += (char)payload[i];
  int l, r;
  if (!parseDrive(msg, l, r)) return;

  if (msg == "S" || l != 0 || r != 0) {
    // Stop button or a real move: take control.
    lastManualMove = millis();
    drive(l, r);
  } else if (manualActive()) {
    // Released buttons while still holding control: keep motors stopped.
    drive(0, 0);
  }
  // Zeros after the hold expires are ignored so the brain keeps control.
}

// ----------------------------------------------------------- battery
float readBattery() {
  if (BATT_PIN < 0) return -1;
  uint32_t mv = 0;
  for (int i = 0; i < 8; i++) mv += analogReadMilliVolts(BATT_PIN);
  return (mv / 8.0f) / 1000.0f * BATT_RATIO;
}

// ----------------------------------------------------------- setup / loop
void setup() {
  Serial.begin(115200);
  int outs[] = {IN1, IN2, IN3, IN4, ENA, ENB, IN1B, IN2B, IN3B, IN4B, ENAB, ENBB};
  for (int p : outs) pinMode(p, OUTPUT);
  stopAll();

  // AP only. Do not call WiFi.setSleep(false): WiFi + BLE needs modem sleep allowed.
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  Serial.println("Manual controller: JVin-Bot / vintech254 / http://192.168.4.1");

  http.on("/", []() { http.send_P(200, "text/html", PAGE); });
  http.on("/ping", []() { http.send(200, "text/plain", "OK"); });
  http.begin();
  ws.begin();
  ws.onEvent(onWs);

  startBle();
  Serial.println("JVin Buddy body ready");
}

void loop() {
  ws.loop();
  http.handleClient();
  uint32_t now = millis();

  // Phone brain commands, unless manual has control.
  if (bleNew) {
    bleNew = false;
    if (!manualActive()) drive(bleL, bleR);
  }

  // Failsafe.
  if (driving && now - lastApplied > FAILSAFE_MS) {
    stopAll();
    Serial.println("Failsafe: no command, motors stopped");
  }

  // Tell phone and page when manual takes or releases control.
  bool m = manualActive();
  if (m != manualWasActive) {
    manualWasActive = m;
    notifyPhone(m ? "M,1" : "M,0");
    ws.broadcastTXT(m ? "M,1" : "M,0");
  }

  // Battery every 2 s.
  if (BATT_PIN >= 0 && now - lastBatt > 2000) {
    lastBatt = now;
    char buf[16];
    snprintf(buf, sizeof(buf), "B,%.2f", readBattery());
    notifyPhone(buf);
    ws.broadcastTXT(buf);
  }

  // Restart advertising after the phone disconnects.
  if (bleWasConnected && !bleConnected) {
    bleWasConnected = false;
    delay(300);
    BLEDevice::startAdvertising();
    Serial.println("Phone disconnected, advertising again");
  }
}
