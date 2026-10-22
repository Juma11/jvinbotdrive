/*
 * JVin-Bot Controller v3 (Vintech Africa)
 * ESP32, 4WD on two L298N boards, own Wi-Fi access point.
 *
 * Manual control only. Same pins and HTTP API as v2, so the JVin Drive app
 * works with either version.
 *
 *   Wi-Fi:  JVin-Bot   password vintech254   page http://192.168.4.1
 *   Board:  ESP32 Arduino core 3.x (needed for analogWrite). No extra libraries.
 *
 * HTTP API
 *   GET /joy?l=-255..255&r=-255..255   set left and right speed
 *   GET /stop                          stop now
 *   GET /ping                          returns OK
 *
 * Failsafe: motors stop if no /joy arrives for 400 ms (Wi-Fi drop, app closed,
 * phone locked). The car never moves again until a new /joy command arrives.
 *
 * Note: GPIO 5 and 15 are boot strapping pins. If uploads fail, unplug the
 * L298N wires on those pins while flashing.
 */

#include <WiFi.h>
#include <WebServer.h>

// Front driver
#define IN1 25
#define IN2 23
#define IN3 15
#define IN4 21
#define ENA 16
#define ENB 17
// Rear driver
#define IN1B 4
#define IN2B 5
#define IN3B 18
#define IN4B 19
#define ENAB 13
#define ENBB 22

const char* AP_SSID = "JVin-Bot";
const char* AP_PASS = "vintech254";
const unsigned long FAILSAFE_MS = 400;

WebServer server(80);
unsigned long lastCmd = 0;
bool moving = false;

// ---------------------------------------------------------------- motors

void side(int en, int a, int b, int enB, int aB, int bB, int spd) {
  spd = constrain(spd, -255, 255);
  bool fwd = spd > 0;
  bool rev = spd < 0;
  int pwm = abs(spd);
  digitalWrite(a, fwd);  digitalWrite(b, rev);
  digitalWrite(aB, fwd); digitalWrite(bB, rev);
  analogWrite(en, pwm);
  analogWrite(enB, pwm);
}

void setMotors(int l, int r) {
  side(ENA, IN1, IN2, ENAB, IN1B, IN2B, l);   // left: front + rear
  side(ENB, IN3, IN4, ENBB, IN3B, IN4B, r);   // right: front + rear
  moving = (l != 0 || r != 0);
}

void stopAll() {
  setMotors(0, 0);
}

// ---------------------------------------------------------------- web page

const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>JVin-Bot</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;user-select:none;-webkit-user-select:none}
body{background:#000;color:#fff;font-family:system-ui,Arial;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:space-between;padding:16px;overflow:hidden}
.top{display:flex;gap:8px;width:100%;justify-content:space-between;align-items:center}
.brand{font-weight:700;letter-spacing:1px}
.pill{padding:5px 12px;border-radius:14px;font-size:13px;font-weight:700;background:#2c2c2e}
.ok{background:#30d158}.bad{background:#ff453a}
#pad{width:min(78vw,340px);height:min(78vw,340px);border-radius:50%;background:#1c1c1e;border:2px solid #00c8ff;position:relative;touch-action:none}
#knob{width:34%;height:34%;border-radius:50%;background:#00c8ff;position:absolute;left:33%;top:33%}
.row{display:flex;gap:8px}
button{border:0;border-radius:14px;padding:10px 16px;font-weight:700;color:#fff;background:#2c2c2e;font-size:14px}
.stop{background:#ff453a}
.sub{color:#8e8e93;font-size:13px}
</style></head><body>
<div class="top"><div class="brand">JVin-Bot</div><div id="link" class="pill">Connecting</div></div>
<div id="pad"><div id="knob"></div></div>
<div class="sub" id="lr">L 0 · R 0</div>
<div class="row">
<button id="s40">40%</button><button id="s70">70%</button><button id="s100">100%</button>
<button class="stop" id="stop">STOP</button>
</div>
<script>
const pad=document.getElementById('pad'),knob=document.getElementById('knob'),link=document.getElementById('link'),lr=document.getElementById('lr');
let L=0,R=0,active=false,timer=null,speed=0.4,lastOk=0;
function setLink(ok){link.textContent=ok?'Connected':'LINK LOST';link.className='pill '+(ok?'ok':'bad');}
function get(u){return fetch(u,{cache:'no-store'}).then(r=>{lastOk=Date.now();setLink(true);return r;}).catch(()=>setLink(false));}
function move(x,y){
  const b=pad.getBoundingClientRect(),cx=b.left+b.width/2,cy=b.top+b.height/2,rad=b.width*0.33;
  let dx=(x-cx)/rad,dy=(y-cy)/rad,d=Math.hypot(dx,dy);if(d>1){dx/=d;dy/=d;}
  knob.style.left=(33+dx*33)+'%';knob.style.top=(33+dy*33)+'%';
  const f=-dy,t=dx;let l=f+t,r=f-t;const m=Math.max(1,Math.abs(l),Math.abs(r));
  L=Math.round(l/m*255*speed);R=Math.round(r/m*255*speed);lr.textContent='L '+L+' · R '+R;
}
function start(e){e.preventDefault();active=true;const p=e.touches?e.touches[0]:e;move(p.clientX,p.clientY);
  if(!timer)timer=setInterval(()=>{if(active)get('/joy?l='+L+'&r='+R);},80);}
function drag(e){if(!active)return;e.preventDefault();const p=e.touches?e.touches[0]:e;move(p.clientX,p.clientY);}
function end(){active=false;clearInterval(timer);timer=null;L=R=0;knob.style.left='33%';knob.style.top='33%';lr.textContent='L 0 · R 0';get('/stop');}
pad.addEventListener('touchstart',start,{passive:false});pad.addEventListener('touchmove',drag,{passive:false});
pad.addEventListener('touchend',end);pad.addEventListener('touchcancel',end);
pad.addEventListener('mousedown',start);document.addEventListener('mousemove',drag);document.addEventListener('mouseup',()=>{if(active)end();});
document.getElementById('stop').onclick=end;
[['s40',.4],['s70',.7],['s100',1]].forEach(([id,v])=>{document.getElementById(id).onclick=()=>{speed=v;};});
setInterval(()=>{if(!active)get('/ping');},1000);
</script></body></html>)HTML";

// ---------------------------------------------------------------- handlers

void handleRoot() {
  server.send_P(200, "text/html", PAGE);
}

void handleJoy() {
  int l = server.arg("l").toInt();
  int r = server.arg("r").toInt();
  setMotors(l, r);
  lastCmd = millis();
  server.send(200, "text/plain", "OK");
}

void handleStop() {
  stopAll();
  server.send(200, "text/plain", "STOPPED");
}

void handlePing() {
  server.send(200, "text/plain", "OK");
}

// ---------------------------------------------------------------- setup / loop

void setup() {
  Serial.begin(115200);
  int pins[] = {IN1, IN2, IN3, IN4, IN1B, IN2B, IN3B, IN4B, ENA, ENB, ENAB, ENBB};
  for (int p : pins) pinMode(p, OUTPUT);
  stopAll();

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  WiFi.setSleep(false);  // lower latency for driving

  server.on("/", handleRoot);
  server.on("/joy", handleJoy);
  server.on("/stop", handleStop);
  server.on("/ping", handlePing);
  server.begin();

  Serial.println("JVin-Bot v3 ready at http://192.168.4.1");
}

void loop() {
  server.handleClient();
  if (moving && millis() - lastCmd > FAILSAFE_MS) {
    stopAll();
    Serial.println("Failsafe stop");
  }
}
