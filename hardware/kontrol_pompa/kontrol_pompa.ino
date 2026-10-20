/*
  KONTROL POMPA + MONITOR 2 TOREN

  Alat asli  : ESP8266 (NodeMCU atau Wemos D1 mini)
  Simulasi   : ESP32 di Wokwi (Wokwi belum mendukung ESP8266, jadi program ini
               sengaja bisa dikompilasi untuk keduanya, logikanya sama persis)

  Fitur
  1. Pompa dikontrol dari aplikasi web (nyala, mati, timer) lewat Firebase.
  2. Saklar manual di D0: ON menyalakan pompa 20 menit, OFF mematikan. Saklar mengalahkan
     aplikasi dan tetap jalan saat offline. Bila saklar sudah ON saat listrik baru
     nyala, diabaikan sampai di OFF lalu ON lagi (agar pompa tidak menyala sendiri).
  3. Dua sensor HC-SR04 membaca ketinggian air toren D7 dan D8. Data dikirim ke
     aplikasi hanya saat tombol Cek di aplikasi ditekan (tidak live).
  4. LCD I2C 16x2 menampilkan toren, pompa, volume, jarak sensor, dan jaringan.
  5. Tiga WiFi: otomatis memilih yang tersedia dan paling kuat, pindah sendiri bila
     putus, dan masuk mode offline bila tidak ada yang tersedia.
  6. Watchdog: bila program hang, alat restart sendiri.
  7. Reboot otomatis sehari sekali (pukul 03:00 bila jam internet tersedia,
     paling lambat setelah 26 jam), hanya saat pompa mati.

  8. Flash lewat WiFi (OTA) dengan ArduinoOTA. Pompa dimatikan dan LCD menampilkan
     progres saat update berlangsung.

  Library (Library Manager): "LiquidCrystal I2C" (Frank de Brabander) dan "ArduinoJson" versi 7.
  Papan: ESP8266 core 3.x (Arduino IDE: NodeMCU 1.0 atau LOLIN D1 mini).
*/

#include <Arduino.h>
#include <Wire.h>
#include <EEPROM.h>
#include <time.h>
#include <stddef.h>
#include <LiquidCrystal_I2C.h>
#include <ArduinoJson.h>
#include <ArduinoOTA.h>      // bawaan core ESP8266 dan ESP32, tidak perlu instal library

#if defined(ESP8266)
  #include <ESP8266WiFi.h>
  #include <ESP8266HTTPClient.h>
  #include <WiFiClientSecureBearSSL.h>
#elif defined(ESP32)
  #include <WiFi.h>
  #include <HTTPClient.h>
  #include <WiFiClientSecure.h>
  #include <esp_task_wdt.h>
  #include <esp_system.h>
#else
  #error "Pilih papan ESP8266 (alat asli) atau ESP32 (hanya untuk simulasi Wokwi)"
#endif

// ---- Tipe data (harus di atas semua fungsi agar Arduino IDE tidak salah membuat prototipe) ----
struct WifiCred { const char* ssid; const char* pass; };
struct Tank {
  const char* id;            // "d7" atau "d8", sama dengan nama di aplikasi
  uint8_t trig, echo;
  float jarak; bool valid;   // jarak sensor ke permukaan air (cm)
  float kosong, penuh;       // kalibrasi (cm)
  float kapasitas;           // liter
  uint32_t n;                // nomor pembacaan, dilihat aplikasi untuk tahu ada data baru
};
struct Persist { uint32_t magic; float kosong[2]; float penuh[2]; float kap[2]; uint32_t crc; };
enum Src : uint8_t { SRC_NONE, SRC_APP, SRC_MANUAL };
struct Pump { bool on; Src src; uint32_t startMs; uint32_t durMs; uint64_t seq; };
struct Sesi { uint32_t startMs, endMs; Src src; char why[8]; uint32_t startEpoch, dur; };   // satu sesi pompa menyala, menunggu dikirim ke riwayat
struct SesiRec { uint32_t start, dur; uint8_t src; char why[7]; };                             // bentuk di EEPROM
struct SesiRom { uint32_t magic, n; SesiRec r[16]; uint32_t crc; };
enum WState { W_IDLE, W_SCANNING, W_CONNECTING, W_ONLINE };

// ============================ PENGATURAN ============================

// Alamat database dan nama folder rahasia. Folder ini adalah "kunci" ke datamu,
// jangan membagikan file ini atau link Wokwi-nya ke orang lain.
const char* DB_HOST = "kontrol-pompa-ad9b6-default-rtdb.asia-southeast1.firebasedatabase.app";
const char* FOLDER  = "ISI_NAMA_FOLDER_RAHASIA";

// Tiga WiFi. Isi nama dan password. Slot yang masih berawalan NAMA_WIFI dianggap kosong.
#if defined(ESP32)
WifiCred WIFI_LIST[3] = { {"Wokwi-GUEST", ""}, {"", ""}, {"", ""} };   // simulasi Wokwi
#else
WifiCred WIFI_LIST[3] = {
  {"NAMA_WIFI_1", "PASSWORD_WIFI_1"},      // WiFi 1, juga dipakai untuk flash OTA
  {"NAMA_WIFI_2", "PASSWORD_WIFI_2"},           // WiFi 2
  {"NAMA_WIFI_3", "PASSWORD_WIFI_3"},     // WiFi 3
};
#endif

// Flash lewat WiFi (OTA). Di Arduino IDE akan muncul port jaringan bernama OTA_HOSTNAME.
const char* OTA_HOSTNAME = "pompa-toren";
const char* OTA_PASSWORD = "ISI_PASSWORD_OTA";   // password yang diminta Arduino IDE saat upload OTA

// Saklar manual.
// true  : saklar ON/OFF (menetap). ON menyalakan pompa 20 menit, OFF mematikan.
//         Setelah 20 menit pompa mati sendiri, saklar harus di OFF lalu ON lagi untuk menyalakan lagi.
// false : tombol tekan (momentary). Sekali tekan menyalakan 20 menit, tekan lagi mematikan.
const bool SWITCH_LATCHING = true;
#if defined(ESP8266)
  // D0 (GPIO16) tidak punya pull-up, tapi punya pull-down internal. Saklar disambung ke 3V3.
  const bool BTN_ACTIVE_LOW = false;
  #define BTN_MODE INPUT_PULLDOWN_16
#else
  const bool BTN_ACTIVE_LOW = true;
  #define BTN_MODE INPUT_PULLUP
#endif

// Modul relay: true bila relay menyala saat pin LOW (paling umum). Ubah bila terbalik.
const bool RELAY_ACTIVE_LOW = true;

const uint32_t MANUAL_RUN_MS = 20UL * 60UL * 1000UL;        // tombol manual: 20 menit
const uint32_t MAX_RUN_MS    = 4UL * 60UL * 60UL * 1000UL;  // batas aman pompa nyala terus
const uint32_t POLL_MS       = 1000;                        // seberapa sering cek perintah
const uint32_t HEARTBEAT_MS  = 10000;                       // lapor "alat hidup", WiFi, dan sinyal ke aplikasi
const uint32_t SENSOR_MS     = 5000;                        // seberapa sering baca sensor untuk LCD
const int      REBOOT_HOUR   = 3;                           // jam reboot harian (WIB)
const long     GMT_OFFSET_S  = 7L * 3600L;                  // WIB
const bool     ENABLE_TEST_COMMANDS = true;                 // perintah uji lewat Serial Monitor

// ============================ PIN ============================
#if defined(ESP8266)
  // NodeMCU: D0=GPIO16, D1=GPIO5, D2=GPIO4, D3=GPIO0, D4=GPIO2, D5=GPIO14, D6=GPIO12, D7=GPIO13, D8=GPIO15
  // Pin sensor mengikuti kabel yang sudah terpasang. Toren D7 = sensor 2 (D7/D8), toren D8 = sensor 1 (D5/D6).
  const uint8_t PIN_SDA    = 4;    // D2  LCD SDA
  const uint8_t PIN_SCL    = 5;    // D1  LCD SCL
  const uint8_t PIN_BTN    = 16;   // D0  saklar manual: satu kaki ke D0, kaki lain ke 3V3 (BUKAN ke GND)
  const uint8_t PIN_RELAY  = 2;    // D4  relay IN (kabel biru)
  const uint8_t PIN_TRIG_A = 13;   // D7  sensor 2 (kabel oranye/kuning) = toren D7, TRIG
  const uint8_t PIN_ECHO_A = 15;   // D8  sensor 2 = toren D7, ECHO
  const uint8_t PIN_TRIG_B = 14;   // D5  sensor 1 (kabel abu abu/ungu) = toren D8, TRIG
  const uint8_t PIN_ECHO_B = 12;   // D6  sensor 1 = toren D8, ECHO
#else
  // Pin ESP32 hanya untuk simulasi Wokwi
  const uint8_t PIN_SDA    = 21;
  const uint8_t PIN_SCL    = 22;
  const uint8_t PIN_BTN    = 4;    // saklar geser ke GND
  const uint8_t PIN_RELAY  = 2;
  const uint8_t PIN_TRIG_A = 5;
  const uint8_t PIN_ECHO_A = 18;
  const uint8_t PIN_TRIG_B = 19;
  const uint8_t PIN_ECHO_B = 23;
#endif

// ============================ UTILITAS ============================
#ifndef NOW_FN
  #define NOW_FN() ((long long)time(nullptr))
#endif

#define LOG(...) do { Serial.printf("[%lu] ", (unsigned long)millis()); Serial.printf(__VA_ARGS__); Serial.println(); } while (0)


static inline bool before(uint32_t t) { return (int32_t)(millis() - t) < 0; }   // aman saat millis() berputar

void u64toa(uint64_t v, char* out) {          // printf %llu tidak andal di ESP8266, jadi manual
  char tmp[21]; int n = 0;
  if (v == 0) tmp[n++] = '0';
  while (v > 0) { tmp[n++] = (char)('0' + (int)(v % 10)); v /= 10; }
  for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
  out[n] = 0;
}
void fmt1(char* out, size_t n, float v) {     // satu desimal tanpa %f
  bool neg = v < 0; if (neg) v = -v;
  long x = (long)(v * 10.0f + 0.5f);
  snprintf(out, n, "%s%ld.%ld", neg ? "-" : "", x / 10, x % 10);
}
uint64_t epochMs() {                          // 0 bila jam internet belum tersedia
  long long t = NOW_FN();
  if (t < 1700000000LL) return 0;
  return (uint64_t)t * 1000ULL + (millis() % 1000);
}

// ============================ WATCHDOG ============================
// ESP8266 sudah punya watchdog bawaan: software ~3 detik dan hardware ~6 detik.
// Selama loop() berjalan lancar, kita "memberi makan" watchdog. Bila program hang,
// watchdog menghentikan dan memulai ulang alat.
void feedWdt() {
#if defined(ESP8266)
  ESP.wdtFeed();
#else
  esp_task_wdt_reset();
#endif
}
void setupWdt() {
#if defined(ESP8266)
  ESP.wdtEnable(8000);
#else
  #if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
    esp_task_wdt_config_t cfg = { 15000, 0, true };   // timeout_ms, idle_core_mask, trigger_panic
    esp_task_wdt_reconfigure(&cfg);
  #else
    esp_task_wdt_init(15, true);
  #endif
  esp_task_wdt_add(NULL);
#endif
}
void delayWdt(uint32_t ms) { uint32_t t0 = millis(); while (millis() - t0 < ms) { feedWdt(); delay(5); } }

const char* resetReasonShort() {
  static char buf[17];
#if defined(ESP8266)
  String r = ESP.getResetReason();
  if (r.indexOf("Watchdog") >= 0)      snprintf(buf, sizeof buf, "Watchdog (WDT)");
  else if (r.indexOf("Exception") >= 0) snprintf(buf, sizeof buf, "Error program");
  else if (r.indexOf("Software") >= 0)  snprintf(buf, sizeof buf, "Reboot terjadwal");
  else if (r.indexOf("Power") >= 0)     snprintf(buf, sizeof buf, "Listrik nyala");
  else if (r.indexOf("External") >= 0)  snprintf(buf, sizeof buf, "Tombol reset");
  else                                  snprintf(buf, sizeof buf, "%.16s", r.c_str());
#else
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  snprintf(buf, sizeof buf, "Listrik nyala"); break;
    case ESP_RST_SW:       snprintf(buf, sizeof buf, "Reboot terjadwal"); break;
    case ESP_RST_PANIC:    snprintf(buf, sizeof buf, "Error program"); break;
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT:      snprintf(buf, sizeof buf, "Watchdog (WDT)"); break;
    case ESP_RST_EXT:      snprintf(buf, sizeof buf, "Tombol reset"); break;
    default:               snprintf(buf, sizeof buf, "Mulai"); break;
  }
#endif
  return buf;
}

// ============================ DATA TOREN ============================
Tank tanks[2] = {
  { "d7", PIN_TRIG_A, PIN_ECHO_A, 0, false, 100, 20, 1000, 0 },
  { "d8", PIN_TRIG_B, PIN_ECHO_B, 0, false, 100, 20, 1000, 0 },
};

bool tankPct(const Tank& t, int& pct, int& liter) {
  if (!t.valid || !(t.kosong > t.penuh)) return false;
  float p = (t.kosong - t.jarak) / (t.kosong - t.penuh) * 100.0f;
  if (p < 0) p = 0; if (p > 100) p = 100;
  pct = (int)(p + 0.5f);
  liter = (int)(p / 100.0f * t.kapasitas + 0.5f);
  return true;
}

// Kalibrasi disimpan di EEPROM agar LCD tetap benar saat offline atau setelah restart.
uint32_t persistCrc(const Persist& p) {
  const uint8_t* b = (const uint8_t*)&p; uint32_t c = 2166136261u;
  for (size_t i = 0; i < offsetof(Persist, crc); i++) { c ^= b[i]; c *= 16777619u; }
  return c;
}
bool calSane(float k, float p, float cap) { return k > p + 5 && p >= 0 && k <= 500 && cap > 0 && cap < 1000000; }
void loadPersist() {
  Persist p; EEPROM.get(0, p);
  if (p.magic != 0xB0A4A001UL || p.crc != persistCrc(p)) return;
  for (int i = 0; i < 2; i++)
    if (calSane(p.kosong[i], p.penuh[i], p.kap[i])) { tanks[i].kosong = p.kosong[i]; tanks[i].penuh = p.penuh[i]; tanks[i].kapasitas = p.kap[i]; }
}
void savePersist() {
  Persist p; memset(&p, 0, sizeof p); p.magic = 0xB0A4A001UL;
  for (int i = 0; i < 2; i++) { p.kosong[i] = tanks[i].kosong; p.penuh[i] = tanks[i].penuh; p.kap[i] = tanks[i].kapasitas; }
  p.crc = persistCrc(p);
  Persist old; EEPROM.get(0, old);
  if (memcmp(&old, &p, sizeof p) != 0) { EEPROM.put(0, p); EEPROM.commit(); LOG("Kalibrasi disimpan ke EEPROM"); }
}

// ============================ POMPA ============================
Pump pump = { false, SRC_NONE, 0, 0, 0 };
bool statusDirty = true;                       // ada perubahan status yang belum terkirim ke aplikasi

void overlay(const char* a, const char* b, uint32_t ms);   // dari bagian LCD

uint32_t relayChangedAt = 0; bool relayLast = false;           // untuk menyegarkan LCD setelah relay berpindah
void relayWrite(bool on) {
  digitalWrite(PIN_RELAY, (on != RELAY_ACTIVE_LOW) ? HIGH : LOW);
  if (on != relayLast) { relayLast = on; relayChangedAt = millis() | 1; }
}

const int SESI_MAX = 16, SESI_ADDR = 64;
Sesi sesiQ[SESI_MAX]; uint8_t sesiN = 0;
uint32_t sesiCrc(const SesiRom& r) {
  const uint8_t* b = (const uint8_t*)&r; uint32_t c = 2166136261u;
  for (size_t i = 0; i < offsetof(SesiRom, crc); i++) { c ^= b[i]; c *= 16777619u; }
  return c;
}
void sesiSave() {                                  // simpan antrean riwayat ke EEPROM agar tidak hilang bila listrik mati
  SesiRom r; memset(&r, 0, sizeof r); r.magic = 0xB0A4A002UL;
  for (int i = 0; i < sesiN; i++) {
    if (sesiQ[i].startEpoch == 0) continue;        // waktu belum diketahui, tidak bisa disimpan
    SesiRec& d = r.r[r.n++];
    d.start = sesiQ[i].startEpoch; d.dur = sesiQ[i].dur; d.src = (uint8_t)sesiQ[i].src;
    snprintf(d.why, sizeof d.why, "%s", sesiQ[i].why);
  }
  r.crc = sesiCrc(r);
  SesiRom old; EEPROM.get(SESI_ADDR, old);
  if (memcmp(&old, &r, sizeof r) != 0) { EEPROM.put(SESI_ADDR, r); EEPROM.commit(); LOG("Antrean riwayat disimpan (%u sesi)", (unsigned)r.n); }
}
void sesiLoad() {
  SesiRom r; EEPROM.get(SESI_ADDR, r);
  if (r.magic != 0xB0A4A002UL || r.crc != sesiCrc(r) || r.n > SESI_MAX) return;
  for (uint32_t i = 0; i < r.n && sesiN < SESI_MAX; i++) {
    Sesi& e = sesiQ[sesiN++]; memset(&e, 0, sizeof e);
    e.startEpoch = r.r[i].start; e.dur = r.r[i].dur; e.src = (Src)r.r[i].src;
    snprintf(e.why, sizeof e.why, "%s", r.r[i].why);
  }
  if (sesiN) LOG("Riwayat tertunda dari memori: %u sesi", (unsigned)sesiN);
}
uint64_t epochMs();
void sesiTutup(const char* why) {                  // dipanggil tepat sebelum status pompa berubah dari nyala
  if (sesiN >= SESI_MAX) { for (int i = 1; i < SESI_MAX; i++) sesiQ[i - 1] = sesiQ[i]; sesiN = SESI_MAX - 1; }
  Sesi& e = sesiQ[sesiN++];
  e.startMs = pump.startMs; e.endMs = millis(); e.src = pump.src;
  snprintf(e.why, sizeof e.why, "%s", why);
  e.dur = (e.endMs - e.startMs + 500UL) / 1000UL;
  uint64_t t = epochMs();
  e.startEpoch = t ? (uint32_t)(t / 1000ULL) - e.dur : 0;
  sesiSave();
}

void pumpStart(Src src, uint32_t durMs, uint64_t seq) {
  if (pump.on) sesiTutup("GANTI");
  pump.on = true; pump.src = src; pump.startMs = millis(); pump.durMs = durMs; pump.seq = seq;
  relayWrite(true); statusDirty = true;
  char b[17];
  if (src == SRC_MANUAL) { snprintf(b, sizeof b, "NYALA %lu MENIT", (unsigned long)(durMs / 60000UL)); overlay("TOMBOL MANUAL", b, 2200); }
  else if (durMs > 0)    { snprintf(b, sizeof b, "NYALA %lu MENIT", (unsigned long)(durMs / 60000UL)); overlay("PERINTAH APP", b, 2200); }
  else                   { overlay("PERINTAH APP", "NYALA TERUS", 2200); }
  LOG("POMPA NYALA sumber=%s durasi=%lu detik", src == SRC_MANUAL ? "MANUAL" : "APP", (unsigned long)(durMs / 1000UL));
}
void pumpStop(Src src, const char* why, uint64_t seq = 0, bool setSeq = false) {
  relayWrite(false);
  bool was = pump.on;
  if (was) sesiTutup(why);
  pump.on = false; pump.src = src; if (setSeq) pump.seq = seq;
  statusDirty = true;
  if (was || !strcmp(why, "APP")) {
    if (!strcmp(why, "MANUAL"))     overlay("TOMBOL MANUAL", "POMPA MATI", 2200);
    else if (!strcmp(why, "APP"))   overlay("PERINTAH APP", "POMPA MATI", 2200);
    else if (!strcmp(why, "TIMER")) overlay("TIMER SELESAI", "POMPA MATI", 3000);
    else                            overlay("BATAS WAKTU", "POMPA MATI", 3000);
  }
  LOG("POMPA MATI alasan=%s", why);
}
void pumpTick() {
  if (!pump.on) return;
  uint32_t el = millis() - pump.startMs;
  if (pump.durMs > 0 && el >= pump.durMs) pumpStop(pump.src, "TIMER");
  else if (el >= MAX_RUN_MS)              pumpStop(pump.src, "BATAS");
}
long pumpRemainSec() {                          // -1 bila tanpa timer
  if (!pump.on || pump.durMs == 0) return -1;
  uint32_t el = millis() - pump.startMs;
  return el >= pump.durMs ? 0 : (long)((pump.durMs - el + 999) / 1000);
}

// ============================ TOMBOL MANUAL ============================
bool btnStable = HIGH, btnLast = HIGH; uint32_t btnChange = 0;
bool swIgnore = false;                                   // saklar sudah ON saat alat dinyalakan: abaikan sampai di OFF
bool swIsOn(bool raw) { return BTN_ACTIVE_LOW ? raw == LOW : raw == HIGH; }
void onButtonPress() {                                   // untuk tombol tekan dan perintah uji 'b'
  if (pump.on) pumpStop(SRC_MANUAL, "MANUAL");
  else         pumpStart(SRC_MANUAL, MANUAL_RUN_MS, 0);
}
void buttonInit() {
  pinMode(PIN_BTN, BTN_MODE);
  delay(20);
  bool raw = digitalRead(PIN_BTN);
  btnStable = btnLast = raw; btnChange = millis();
  swIgnore = false;
}
void pollButton() {
  bool r = digitalRead(PIN_BTN);
  if (r != btnLast) { btnLast = r; btnChange = millis(); }
  if (millis() - btnChange > 25 && r != btnStable) {
    btnStable = r;
    bool on = swIsOn(r);
    if (SWITCH_LATCHING) {
      if (on) {
        pumpStart(SRC_MANUAL, MANUAL_RUN_MS, 0);                 // saklar ON: langsung nyala 20 menit, setiap kali
      } else {
        swIgnore = false;
        if (pump.on) pumpStop(SRC_MANUAL, "MANUAL");             // saklar OFF: langsung mati, apa pun sumbernya
      }
    } else if (on) onButtonPress();
  }
  // Pengaman: pompa manual tidak boleh tetap nyala bila saklar sudah OFF (misalnya perubahan terlewat)
  if (SWITCH_LATCHING && pump.on && pump.src == SRC_MANUAL && !swIsOn(btnStable) && !swIsOn(r) && millis() - btnChange > 25)
    pumpStop(SRC_MANUAL, "MANUAL");
}
void waitMs(uint32_t ms) { uint32_t t0 = millis(); while (millis() - t0 < ms) { feedWdt(); pollButton(); delay(2); } }

// ============================ SENSOR ============================
float readOnce(uint8_t trig, uint8_t echo) {
  digitalWrite(trig, LOW);  delayMicroseconds(3);
  digitalWrite(trig, HIGH); delayMicroseconds(20);   // pulsa lebih panjang untuk kabel panjang
  digitalWrite(trig, LOW);
  unsigned long d = pulseIn(echo, HIGH, 30000UL);          // 30 ms, kira-kira 5 meter
  if (d == 0) return -1;
  return (float)d * 0.0343f / 2.0f;
}
uint32_t lastGoodMs[2] = { 0, 0 };
bool measure(Tank& t, int samples) {                       // nilai tengah, dengan percobaan ulang (kabel panjang sering gagal sesekali)
  float v[9]; int n = 0;
  if (samples > 9) samples = 9;
  for (int round = 0; round < 3 && n == 0; round++) {
    for (int i = 0; i < samples; i++) {
      float x = readOnce(t.trig, t.echo);
      if (x >= 2.0f && x <= 400.0f) v[n++] = x;
      waitMs(60);
    }
  }
  int k2 = (t.id[1] == '8') ? 1 : 0;
  if (n == 0) {
    LOG("Sensor %s gagal, nilai lama dipertahankan", t.id);   // nilai di LCD dan aplikasi hanya berganti bila ada angka baru
    return false;
  }
  for (int i = 1; i < n; i++) { float k = v[i]; int j = i - 1; while (j >= 0 && v[j] > k) { v[j + 1] = v[j]; j--; } v[j + 1] = k; }
  t.jarak = (float)((int)(v[n / 2] * 10.0f + 0.5f)) / 10.0f; t.valid = true; lastGoodMs[k2] = millis();
  if (n < samples) LOG("Sensor %s: %d dari %d sampel terbaca", t.id, n, samples);
  return true;
}

// ============================ LCD ============================
LiquidCrystal_I2C* lcd = nullptr;
uint8_t L0[16], L1[16], shown[2][16]; bool shownOk[2] = { false, false };
uint8_t charset = 255;
enum { CS_TANK = 0, CS_REBOOT = 1 };
enum { CH_WIFI = 6, CH_DROP = 7, CH_BSLASH = 7 };

const uint8_t G_WIFI[8]  = { 0x00, 0x0E, 0x11, 0x04, 0x0A, 0x00, 0x04, 0x00 };
const uint8_t G_DROP[8]  = { 0x04, 0x04, 0x0E, 0x0E, 0x1F, 0x1F, 0x0E, 0x00 };
const uint8_t G_BSL[8]   = { 0x00, 0x10, 0x08, 0x04, 0x02, 0x01, 0x00, 0x00 };

void loadCharset(uint8_t cs) {
  if (charset == cs) return;
  charset = cs;
  uint8_t g[8];
  for (int L = 0; L <= 5; L++) {                   // 0..5 kolom terisi, dengan garis atas dan bawah
    uint8_t m = (uint8_t)((0x1F << (5 - L)) & 0x1F);
    for (int r = 0; r < 8; r++) g[r] = (r == 0 || r == 7) ? 0x1F : m;
    lcd->createChar(L, g);
  }
  if (cs == CS_TANK) {
    memcpy(g, G_WIFI, 8); lcd->createChar(6, g);
    memcpy(g, G_DROP, 8); lcd->createChar(7, g);
  } else {
    memset(g, 0, 8);      lcd->createChar(6, g);
    memcpy(g, G_BSL, 8);  lcd->createChar(7, g);
  }
  shownOk[0] = shownOk[1] = false;
}
void flushLine(uint8_t row, const uint8_t* b) {     // tulis hanya bila isinya berubah (tanpa kedip)
  if (shownOk[row] && memcmp(shown[row], b, 16) == 0) return;
  lcd->setCursor(0, row);
  for (int i = 0; i < 16; i++) lcd->write(b[i]);
  memcpy(shown[row], b, 16); shownOk[row] = true;
}
void clr() { memset(L0, ' ', 16); memset(L1, ' ', 16); }
void txt(uint8_t* L, int col, const char* s) { for (int i = 0; s[i] && col + i < 16; i++) if (col + i >= 0) L[col + i] = (uint8_t)s[i]; }
void center(uint8_t* L, const char* s) { int n = (int)strlen(s); if (n > 16) n = 16; txt(L, (16 - n) / 2, s); }
void present() { flushLine(0, L0); flushLine(1, L1); }
void barRow(uint8_t* L, int col, int cells, int pct) {
  int total = pct * cells * 5 / 100;
  for (int i = 0; i < cells; i++) { int f = total - i * 5; if (f < 0) f = 0; if (f > 5) f = 5; L[col + i] = (uint8_t)f; }
}

char ovl0[17] = "", ovl1[17] = ""; uint32_t ovlUntil = 0;
void overlay(const char* a, const char* b, uint32_t ms) {
  snprintf(ovl0, sizeof ovl0, "%s", a); snprintf(ovl1, sizeof ovl1, "%s", b); ovlUntil = millis() + ms;
}

// ============================ WIFI (tiga jaringan, pindah otomatis) ============================
WState ws = W_IDLE;
int curIdx = -1; uint32_t wsStart = 0, lastScanEnd = 0, offlineSince = 0;
uint32_t retryAt[3] = { 0, 0, 0 }; bool avoidOn[3] = { false, false, false };   // jaringan yang sedang dihindari sementara
void avoidWifi(int k, uint32_t ms) { retryAt[k] = millis() + ms; avoidOn[k] = true; }
bool wifiEverOnline = false, firstScan = true;
char curSsid[33] = "";
const uint32_t SCAN_GAP_MS = 6000, CONNECT_TIMEOUT_MS = 15000, AVOID_MS = 30000;

bool slotUsed(int i) { const char* s = WIFI_LIST[i].ssid; return s && s[0] && strncmp(s, "NAMA_WIFI", 9) != 0; }

// Variabel layanan cloud yang dipakai wifiLoop
uint32_t lastCloudOk = 0; int cloudFail = 0; bool cmdSynced = false;

void onOnline() {
  ws = W_ONLINE; wifiEverOnline = true;
  snprintf(curSsid, sizeof curSsid, "%s", WIFI_LIST[curIdx >= 0 ? curIdx : 0].ssid);
  configTime(GMT_OFFSET_S, 0, "pool.ntp.org", "time.google.com");
  uint32_t gone = millis() - offlineSince;
  if (offlineSince != 0 && gone > 120000UL) cmdSynced = false;   // perintah lama tidak boleh dijalankan ulang
  lastCloudOk = millis(); cloudFail = 0; statusDirty = true;
  overlay("WiFi tersambung", curSsid, 2500);
  LOG("WiFi tersambung ke %s", curSsid);
}
void onOffline() {
  ws = W_IDLE; offlineSince = millis(); lastScanEnd = 0;
  WiFi.disconnect();
  overlay("WiFi terputus", "Mencari jaringan", 2500);
  LOG("WiFi terputus, mencari jaringan lain");
}
void dropCurrentWifi(uint32_t avoidMs) {          // dipakai bila WiFi ada tapi internet mati
  if (curIdx >= 0) avoidWifi(curIdx, avoidMs);
  onOffline();
}
void wifiLoop() {
  bool conn = (WiFi.status() == WL_CONNECTED);
  switch (ws) {
    case W_ONLINE:
      if (!conn) onOffline();
      break;
    case W_CONNECTING:
      if (conn) onOnline();
      else if (millis() - wsStart > CONNECT_TIMEOUT_MS) {
        if (curIdx >= 0) avoidWifi(curIdx, AVOID_MS);
        WiFi.disconnect(); ws = W_IDLE; lastScanEnd = millis();
        LOG("Gagal tersambung ke %s", WIFI_LIST[curIdx].ssid);
      }
      break;
    case W_SCANNING: {
      int n = WiFi.scanComplete();
      if (n >= 0) {
        int best = -1, bestRssi = -1000;
        for (int i = 0; i < n; i++) {
          String s = WiFi.SSID(i); int r = WiFi.RSSI(i);
          for (int k = 0; k < 3; k++)
            if (slotUsed(k) && s == WIFI_LIST[k].ssid && (!avoidOn[k] || (int32_t)(millis() - retryAt[k]) >= 0) && r > bestRssi) { best = k; bestRssi = r; }
        }
        WiFi.scanDelete();
        if (best >= 0) {
          curIdx = best; WiFi.begin(WIFI_LIST[best].ssid, WIFI_LIST[best].pass);
          ws = W_CONNECTING; wsStart = millis();
          LOG("Menyambung ke %s (sinyal %d dBm)", WIFI_LIST[best].ssid, bestRssi);
        } else { ws = W_IDLE; lastScanEnd = millis(); }
      } else if (n == WIFI_SCAN_FAILED || millis() - wsStart > 12000) { ws = W_IDLE; lastScanEnd = millis(); }
      break;
    }
    case W_IDLE:
      if (conn) { onOnline(); break; }
      if (firstScan || millis() - lastScanEnd >= SCAN_GAP_MS) {
        firstScan = false; WiFi.scanNetworks(true, false); ws = W_SCANNING; wsStart = millis();
      }
      break;
  }
}
void wifiBegin() {
  WiFi.persistent(false); WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(false); WiFi.disconnect();
  offlineSince = millis();
}

// ============================ CLOUD (Firebase REST) ============================
#if defined(ESP8266)
BearSSL::WiFiClientSecure secureClient;
#else
WiFiClientSecure secureClient;
#endif
HTTPClient http;

bool httpReq(const char* method, const char* path, const char* body, String* out, const char* suffix = "") {
  char url[220];
  if (path[0]) snprintf(url, sizeof url, "https://%s/data/%s%s/%s.json", DB_HOST, FOLDER, suffix, path);
  else         snprintf(url, sizeof url, "https://%s/data/%s%s.json", DB_HOST, FOLDER, suffix);   // seluruh folder
  uint32_t t0 = millis();
  LOG("HTTP %s %s mulai, heap=%u", method, path[0] ? path : "(folder)", (unsigned)ESP.getFreeHeap());
  http.setReuse(true); http.setTimeout(1500);
  if (!http.begin(secureClient, url)) return false;
  int code;
  if (body) { http.addHeader("Content-Type", "application/json"); code = http.sendRequest(method, String(body)); }
  else code = http.GET();
  bool ok = (code == 200);
  if (ok && out) *out = http.getString();
  http.end();
  LOG("HTTP %s selesai kode=%d dalam %lu ms, heap=%u", method, code, (unsigned long)(millis() - t0), (unsigned)ESP.getFreeHeap());
  if (!ok) { secureClient.stop(); LOG("HTTP %s %s gagal kode=%d", method, path, code); }
  return ok;
}

bool publishStatus() {
  char seqs[24], body[140];
  uint64_t seq = pump.seq;
  if (pump.src == SRC_MANUAL && pump.on) {                 // tombol manual: awal nyala dihitung dari jam internet
    uint64_t e = epochMs();
    seq = e ? e - (uint64_t)(millis() - pump.startMs) : 0;
  }
  u64toa(seq, seqs);
  snprintf(body, sizeof body, "{\"state\":\"%s\",\"durasi\":%lu,\"seq\":%s,\"sumber\":\"%s\"}",
           pump.on ? "ON" : "OFF", (unsigned long)((pump.on ? pump.durMs : 0) / 1000UL), seqs,
           pump.src == SRC_MANUAL ? "MANUAL" : (pump.src == SRC_APP ? "APP" : "-"));
  return httpReq("PUT", "pompa/status", body, nullptr);
}

bool publishReading(int i, uint32_t remoteN) {         // hanya mengirim bila ada angka valid, tidak pernah menimpa dengan kosong
  Tank& t = tanks[i];
  if (!t.valid) return false;
  char path[16], j[12], body[110];
  snprintf(path, sizeof path, "toren/%s", t.id);
  t.n = (t.n > remoteN ? t.n : remoteN) + 1;
  fmt1(j, sizeof j, t.jarak);
  snprintf(body, sizeof body, "{\"jarak\":%s,\"n\":%lu,\"t\":{\".sv\":\"timestamp\"}}", j, (unsigned long)t.n);
  return httpReq("PATCH", path, body, nullptr);
}

void jsonEsc(char* out, size_t n, const char* in) {
  size_t k = 0;
  for (; *in && k + 2 < n; in++) { if (*in == '"' || *in == '\\') out[k++] = '\\'; out[k++] = *in; }
  out[k] = 0;
}
bool publishPresence() {                              // detak alat: aplikasi menganggap alat terputus bila detak berhenti
  char ss[72], body[200];
  jsonEsc(ss, sizeof ss, curSsid);
  snprintf(body, sizeof body, "{\"ssid\":\"%s\",\"rssi\":%d,\"up\":%lu,\"ts\":{\".sv\":\"timestamp\"}}",
           ss, (int)WiFi.RSSI(), (unsigned long)(millis() / 1000UL));
  return httpReq("PUT", "perangkat", body, nullptr);
}

bool sesiKirim() {                                    // kirim sesi pompa yang sudah selesai ke riwayat (di folder terpisah)
  bool any = false;
  while (sesiN > 0) {
    Sesi& q = sesiQ[0];
    if (q.startEpoch == 0) {                          // dicatat saat jam internet belum ada: hitung sekarang
      uint64_t e = epochMs();
      if (!e) return any;
      q.startEpoch = (uint32_t)(e / 1000ULL) - (millis() - q.endMs) / 1000UL - q.dur;
      sesiSave();
    }
    char body[150];
    snprintf(body, sizeof body, "{\"mulai\":%lu,\"selesai\":%lu,\"detik\":%lu,\"sumber\":\"%s\",\"alasan\":\"%s\"}",
             (unsigned long)q.startEpoch, (unsigned long)(q.startEpoch + q.dur), (unsigned long)q.dur,
             q.src == SRC_MANUAL ? "MANUAL" : "APP", q.why);
    if (q.dur >= 1 && !httpReq("POST", "", body, nullptr, "_riwayat")) return any;
    for (int i = 1; i < sesiN; i++) sesiQ[i - 1] = sesiQ[i];
    sesiN--; any = true;
    sesiSave();
  }
  return any;
}

uint64_t lastCek[2] = { 0, 0 };

void cloudSync() {
  String payload;
  if (!httpReq("GET", "", nullptr, &payload)) {            // path kosong = seluruh folder
    cloudFail++;
    if (cloudFail >= 4 && millis() - lastCloudOk > 10UL * 60UL * 1000UL) {   // WiFi ada tapi internet mati
      LOG("Internet tidak terjangkau 10 menit, coba jaringan lain");
      cloudFail = 0; lastCloudOk = millis();
      dropCurrentWifi(60000);
    }
    return;
  }
  cloudFail = 0; lastCloudOk = millis();

  JsonDocument doc;
  if (deserializeJson(doc, payload.c_str())) { LOG("JSON tidak bisa dibaca"); return; }
  JsonVariant root = doc.as<JsonVariant>();

  // 1. Kalibrasi dari aplikasi
  bool calChanged = false;
  for (int i = 0; i < 2; i++) {
    JsonVariant t = root["toren"][tanks[i].id];
    if (t.isNull()) continue;
    { uint32_t rn = t["n"] | 0U; if (rn > tanks[i].n) tanks[i].n = rn; }
    float k = t["kosong"] | tanks[i].kosong, p = t["penuh"] | tanks[i].penuh, c = t["kapasitas"] | tanks[i].kapasitas;
    if (calSane(k, p, c) && (fabsf(k - tanks[i].kosong) > 0.001f || fabsf(p - tanks[i].penuh) > 0.001f || fabsf(c - tanks[i].kapasitas) > 0.001f)) {
      tanks[i].kosong = k; tanks[i].penuh = p; tanks[i].kapasitas = c; calChanged = true;
    }
  }
  if (calChanged) savePersist();

  // 2. Perintah pompa
  JsonVariant cmd = root["pompa"]["cmd"];
  if (!cmd.isNull()) {
    uint64_t seq = cmd["seq"] | 0ULL;
    const char* st = cmd["state"] | "OFF";
    uint32_t durS = cmd["durasi"] | 0U;
    static uint64_t lastSeq = 0;
    if (!cmdSynced) {                                      // setelah restart atau lama offline: perintah lama tidak dijalankan
      cmdSynced = true; lastSeq = seq; pump.seq = seq; statusDirty = true;
      LOG("Perintah lama (seq terakhir) diabaikan demi keamanan");
    } else if (seq != 0 && seq != lastSeq) {
      lastSeq = seq;
      if (!strcmp(st, "ON")) pumpStart(SRC_APP, durS * 1000UL, seq);
      else                   pumpStop(SRC_APP, "APP", seq, true);
    }
  } else cmdSynced = true;

  // 3. Kirim status pompa bila ada perubahan
  if (statusDirty && publishStatus()) statusDirty = false;

  // 4. Permintaan cek volume dari tombol di aplikasi
  for (int i = 0; i < 2; i++) {
    uint64_t c = root["cek"][tanks[i].id] | 0ULL;
    if (c != 0 && c != lastCek[i]) {
      lastCek[i] = c;
      overlay("Cek dari aplikasi", "Membaca sensor...", 2500);
      measure(tanks[i], 7);
      publishReading(i, root["toren"][tanks[i].id]["n"] | 0U);   // tanpa angka valid: tidak mengirim apa pun
      LOG("Cek %s: %s", tanks[i].id, tanks[i].valid ? "terkirim" : "sensor gagal");
    }
  }
}

// ============================ TAMPILAN LCD ============================
// Layar bergantian terus (looping). Pompa dan WiFi muncul berulang agar selalu terlihat.
// 0 toren (persen), 1 pompa dan timer, 2 volume liter, 3 jarak sensor, 4 WiFi
const uint8_t  VIEW_SEQ[7] = { 0, 1, 4, 2, 0, 1, 3 };
const uint16_t VIEW_MS[7]  = { 3500, 3000, 3000, 3000, 3500, 3000, 2500 };
int viewPos = 6; uint32_t viewUntil = 0;
int viewIdx = 0;

void scrollText(uint8_t* L, const char* t, uint32_t now) {      // teks lebih dari 16 huruf digeser pelan
  int n = (int)strlen(t);
  if (n <= 16) { txt(L, 0, t); return; }
  int span = n - 16 + 4, off = (int)((now / 500) % span) - 2;    // berhenti sebentar di awal dan akhir
  if (off < 0) off = 0; if (off > n - 16) off = n - 16;
  for (int i = 0; i < 16; i++) L[i] = (uint8_t)t[off + i];
}

void drawTankRow(uint8_t* L, const char* label, const Tank& t) {
  int pct = 0, liter = 0; bool ok = tankPct(t, pct, liter);
  txt(L, 0, label);
  barRow(L, 2, 8, ok ? pct : 0);
  char b[8];
  if (ok) snprintf(b, sizeof b, "%3d%%", pct); else snprintf(b, sizeof b, " --%%");
  txt(L, 11, b);
}

void drawUI() {
  uint32_t now = millis();
  loadCharset(CS_TANK);
  if (before(ovlUntil)) { clr(); center(L0, ovl0); center(L1, ovl1); present(); return; }
  if (!before(viewUntil)) { viewPos = (viewPos + 1) % 7; viewIdx = VIEW_SEQ[viewPos]; viewUntil = now + VIEW_MS[viewPos]; }
  bool blink = (now / 500) & 1;
  clr();
  char a[24], b[24], j[12];
  int pct, liter;
  switch (viewIdx) {
    case 0:                                                  // dua toren dengan batang
      drawTankRow(L0, "D7", tanks[0]); drawTankRow(L1, "D8", tanks[1]);
      L0[15] = (ws == W_ONLINE) ? CH_WIFI : (blink ? 'x' : ' ');
      L1[15] = pump.on ? (blink ? CH_DROP : ' ') : ' ';
      break;
    case 1: {                                                // pompa
      if (pump.on) {
        txt(L0, 0, "Pompa: NYALA");
        long s = pumpRemainSec();
        if (s >= 0) snprintf(a, sizeof a, "Sisa %02ld:%02ld", s / 60, s % 60);
        else { uint32_t e = (millis() - pump.startMs) / 1000UL; snprintf(a, sizeof a, "Jalan %02lu:%02lu", (unsigned long)(e / 60), (unsigned long)(e % 60)); }
        txt(L1, 0, a); txt(L1, 13, pump.src == SRC_MANUAL ? "MAN" : "APP");
      } else { txt(L0, 0, "Pompa: MATI"); txt(L1, 0, "Tombol: 20 menit"); }
      break;
    }
    case 2:                                                  // volume
      for (int i = 0; i < 2; i++) {
        uint8_t* L = i ? L1 : L0;
        if (tankPct(tanks[i], pct, liter)) snprintf(a, sizeof a, "D%d %4d L %3d%%", i ? 8 : 7, liter, pct);
        else                               snprintf(a, sizeof a, "D%d   -- L  --%%", i ? 8 : 7);
        txt(L, 0, a);
      }
      break;
    case 3:                                                  // jarak mentah, berguna saat kalibrasi
      for (int i = 0; i < 2; i++) {
        uint8_t* L = i ? L1 : L0;
        if (tanks[i].valid) { fmt1(j, sizeof j, tanks[i].jarak); snprintf(a, sizeof a, "D%d jarak %scm", i ? 8 : 7, j); }
        else                  snprintf(a, sizeof a, "D%d sensor error", i ? 8 : 7);
        txt(L, 0, a);
      }
      break;
    default:                                                 // jaringan yang dipakai
      if (ws == W_ONLINE) {
        scrollText(L0, curSsid, now);
        if (cloudFail >= 2) txt(L1, 0, "Server gagal");
        else { snprintf(b, sizeof b, "W%d %ddBm Online", curIdx + 1, (int)WiFi.RSSI()); txt(L1, 0, b); }
      } else if (ws == W_CONNECTING) {
        txt(L0, 0, "Menyambung..."); scrollText(L1, WIFI_LIST[curIdx >= 0 ? curIdx : 0].ssid, now);
      } else {
        txt(L0, 0, "MODE OFFLINE");
        const char* dots[4] = { "Mencari WiFi   ", "Mencari WiFi.  ", "Mencari WiFi.. ", "Mencari WiFi..." };
        txt(L1, 0, dots[(now / 400) % 4]);
      }
      break;
  }
  present();
}

void bootAnimation() {
  loadCharset(CS_TANK);
  for (int r = 0; r < 2; r++) { clr(); (r == 0 ? L0 : L1)[7] = CH_DROP; present(); delayWdt(220); }   // tetes air jatuh
  clr(); txt(L1, 4, ".:o:."); present(); delayWdt(150);
  clr(); txt(L1, 2, "~.:o:.~"); present(); delayWdt(150);
  clr(); present();
  const char* title = "KONTROL POMPA";                                   // judul mengetik
  int n = (int)strlen(title), c0 = (16 - n) / 2;
  for (int i = 0; i < n; i++) { L0[c0 + i] = (uint8_t)title[i]; present(); delayWdt(55); }
  center(L1, "Pompa + 2 Toren"); present(); delayWdt(500);

  const char* steps[5] = { "Muat kalibrasi", "Tes sensor D7", "Tes sensor D8", "Siapkan WiFi", "Siap!" };
  for (int s = 0; s < 5; s++) {                                          // langkah awal sungguhan dengan bar kemajuan
    memset(L0, ' ', 16); center(L0, steps[s]);
    if (s == 0) { loadPersist(); sesiLoad(); }
    if (s == 1) measure(tanks[0], 5);
    if (s == 2) measure(tanks[1], 5);
    if (s == 3) wifiBegin();
    for (int k = 1; k <= 5; k++) { memset(L1, ' ', 16); barRow(L1, 0, 16, (s * 5 + k) * 4); present(); delayWdt(50); }
  }
  clr(); center(L0, "Mulai dari:"); center(L1, resetReasonShort()); present(); delayWdt(1300);
}

void rebootAnimation(const char* reason) {
  loadCharset(CS_REBOOT);
  const char spin[4] = { '|', '/', '-', (char)CH_BSLASH };
  for (int step = 0; step <= 30; step++) {                               // hitung mundur 3 detik
    clr(); center(L0, reason);
    L1[0] = (uint8_t)('0' + (3 - step / 10 < 1 ? 1 : 3 - step / 10));
    int cells = 12 * (30 - step) / 30;
    for (int i = 0; i < 12; i++) L1[2 + i] = (uint8_t)(i < cells ? 5 : 0);
    L1[15] = (uint8_t)spin[step % 4];
    present(); delayWdt(100);
  }
  clr(); center(L0, "Mulai ulang..."); present(); delayWdt(300);
}
void rebootNow(const char* reason) {
  relayWrite(false);
  LOG("REBOOT: %s", reason);
  rebootAnimation(reason);
  ESP.restart();
}

// Reboot harian dan pemulihan: hanya saat pompa mati
void checkAutoReboot() {
  if (pump.on) return;
  uint32_t up = millis();
  long long t = NOW_FN();
  if (t >= 1700000000LL) {
    time_t tt = (time_t)t; struct tm lt; localtime_r(&tt, &lt);
    if (lt.tm_hour == REBOOT_HOUR && up >= 12UL * 3600UL * 1000UL) rebootNow("REBOOT HARIAN");
  }
  if (up >= 26UL * 3600UL * 1000UL) rebootNow("REBOOT HARIAN");
  if (millis() - lastCloudOk > 3UL * 3600UL * 1000UL && up > 3UL * 3600UL * 1000UL) rebootNow("PULIHKAN WIFI");
}

// ============================ PERINTAH UJI (Serial Monitor) ============================
void pollSerial() {
  if (!ENABLE_TEST_COMMANDS) return;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == 'h') { LOG("TES: sengaja hang untuk menguji watchdog"); while (true) { } }
    else if (c == 'r') rebootNow("TES REBOOT");
    else if (c == 's') LOG("pompa=%s sisa=%ld wifi=%d toren7=%.1f toren8=%.1f", pump.on ? "ON" : "OFF", pumpRemainSec(), (int)ws, tanks[0].jarak, tanks[1].jarak);
  }
}

// ============================ SETUP DAN LOOP ============================
LiquidCrystal_I2C* makeLcd() {
  uint8_t addr = 0x27;
  Wire.beginTransmission(0x27);
  if (Wire.endTransmission() != 0) { Wire.beginTransmission(0x3F); if (Wire.endTransmission() == 0) addr = 0x3F; }
  LOG("LCD alamat 0x%02X", addr);
  return new LiquidCrystal_I2C(addr, 16, 2);
}

uint32_t tSensor = 0, tPoll = 0, tLcd = 0;

void setup() {
  digitalWrite(PIN_RELAY, (RELAY_ACTIVE_LOW ? HIGH : LOW));   // relay mati dulu sebelum jadi output
  pinMode(PIN_RELAY, OUTPUT);
  buttonInit();
  for (int i = 0; i < 2; i++) { pinMode(tanks[i].trig, OUTPUT); digitalWrite(tanks[i].trig, LOW); pinMode(tanks[i].echo, INPUT); }
  Serial.begin(115200);
  setupWdt();
  Wire.begin(PIN_SDA, PIN_SCL);
  EEPROM.begin(512);
  lcd = makeLcd(); lcd->init(); lcd->backlight();
#if defined(ESP8266)
  secureClient.setInsecure(); secureClient.setBufferSizes(1024, 512);
#else
  secureClient.setInsecure();
#endif
  LOG("Mulai. Penyebab restart: %s", resetReasonShort());
  bootAnimation();
  lastCloudOk = millis();
  if (SWITCH_LATCHING && swIsOn(btnStable)) pumpStart(SRC_MANUAL, MANUAL_RUN_MS, 0);   // saklar sudah ON saat alat menyala
}

// Percikan listrik relay bisa mengacaukan isi LCD walau program masih jalan. Program hanya menulis
// baris yang berubah, jadi layar rusak tidak pulih sendiri. Karena itu semua baris ditulis ulang
// tiap 1,5 detik dan karakter khusus dikirim ulang sesaat setelah relay berpindah.
uint32_t lcdLastFull = 0;
void lcdMaintain() {
  uint32_t n = millis();
  if (relayChangedAt && n - relayChangedAt > 150) { relayChangedAt = 0; charset = 255; shownOk[0] = shownOk[1] = false; lcdLastFull = n; }
  else if (n - lcdLastFull > 1500UL) { shownOk[0] = shownOk[1] = false; lcdLastFull = n; }
}

// ============================ FLASH LEWAT WIFI (OTA) ============================
bool otaBusy = false, otaStarted = false;
uint32_t otaLast = 0;
void otaSetup() {
  ArduinoOTA.setHostname(OTA_HOSTNAME);
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.onStart([]() {
    otaBusy = true;
    relayWrite(false); pump.on = false; pump.src = SRC_NONE;   // pompa dimatikan demi keamanan
    loadCharset(CS_REBOOT);
    clr(); center(L0, "UPDATE PROGRAM"); center(L1, "Jangan dicabut"); present();
    LOG("OTA mulai");
  });
  ArduinoOTA.onProgress([](unsigned int done, unsigned int total) {
    feedWdt();
    int pct = total ? (int)((uint64_t)done * 100 / total) : 0;
    if (pct == (int)otaLast && done != total) return;
    otaLast = pct;
    clr(); center(L0, "UPDATE PROGRAM");
    int cells = 10 * pct / 100;
    for (int i = 0; i < 10; i++) L1[1 + i] = (uint8_t)(i < cells ? 5 : 0);
    char b[8]; snprintf(b, sizeof b, "%3d%%", pct); txt(L1, 12, b);
    present();
  });
  ArduinoOTA.onEnd([]() {
    clr(); center(L0, "UPDATE SELESAI"); center(L1, "Mulai ulang..."); present();
    LOG("OTA selesai");
  });
  ArduinoOTA.onError([](ota_error_t e) {
    otaBusy = false;
    char b[17]; snprintf(b, sizeof b, "Kode galat %d", (int)e);
    overlay("UPDATE GAGAL", b, 4000);
    LOG("OTA galat %d", (int)e);
  });
  ArduinoOTA.begin();
  otaStarted = true;
  LOG("OTA siap sebagai %s", OTA_HOSTNAME);
}

void loop() {
  feedWdt();
  static uint32_t tBeat = 0;
  if (millis() - tBeat >= 5000) { tBeat = millis(); LOG("hidup, heap=%u, wifi=%d, pompa=%d", (unsigned)ESP.getFreeHeap(), (int)ws, (int)pump.on); }
  if (ws == W_ONLINE) {
    if (!otaStarted) { otaSetup(); }
    ArduinoOTA.handle();
  }
  if (otaBusy) { delay(1); return; }          // saat flash berlangsung, tidak ada pekerjaan lain
  pollButton();
  pollSerial();
  pumpTick(); 
  wifiLoop(); 
  uint32_t now = millis();
  if (now - tSensor >= SENSOR_MS) {
    tSensor = now;
    bool fresh[2] = { measure(tanks[0], 5), measure(tanks[1], 5) };
    static float lastPub[2] = { -1000, -1000 }; static uint32_t lastPubMs[2] = { 0, 0 };
    for (int i = 0; i < 2 && ws == W_ONLINE && cloudFail == 0; i++) {      // kirim ke aplikasi hanya bila ada angka baru yang berubah berarti
      if (!fresh[i]) continue;
      if (fabsf(tanks[i].jarak - lastPub[i]) >= 1.0f || millis() - lastPubMs[i] >= 300000UL) {
        if (publishReading(i, 0)) { lastPub[i] = tanks[i].jarak; lastPubMs[i] = millis(); }
      }
    }
  }
  static uint32_t tHb = 0, tSes = 0;
  if (ws == W_ONLINE && cloudFail == 0 && millis() - tHb >= HEARTBEAT_MS) { tHb = millis(); publishPresence(); }
  if (ws == W_ONLINE && cloudFail == 0 && sesiN > 0 && millis() - tSes >= 3000) { tSes = millis(); sesiKirim(); }
  static uint32_t tPush = 0;
  if (ws == W_ONLINE && cmdSynced && statusDirty && millis() - tPush >= 700) { tPush = millis(); if (publishStatus()) statusDirty = false; }   // kabari aplikasi segera
  if (ws == W_ONLINE && millis() - tPoll >= POLL_MS) { tPoll = millis(); cloudSync(); }
  checkAutoReboot(); 
  if (millis() - tLcd >= 250) { tLcd = millis(); lcdMaintain(); drawUI(); }
  delay(1);
}
