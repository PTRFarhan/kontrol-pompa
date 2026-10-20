# Pin mapping NodeMCU ESP8266 (final)

| Komponen | Pin NodeMCU | GPIO | Kabel |
|---|---|---|---|
| LCD SDA | D2 | 4 | SDA |
| LCD SCL | D1 | 5 | SCL |
| LCD VCC / GND | VIN (5V) / GND | | |
| Saklar manual | D0 ke 3V3 | 16 | satu kaki D0, kaki lain 3V3 (bukan GND) |
| Relay IN | D4 | 2 | biru |
| Relay VCC / GND | VIN (5V) / GND | | ungu / hijau |
| Sensor 1 (toren D8) TRIG | D5 | 14 | abu abu |
| Sensor 1 ECHO | D6 | 12 | ungu (lewat pembagi 1k/2k) |
| Sensor 2 (toren D7) TRIG | D7 | 13 | oranye |
| Sensor 2 ECHO | D8 | 15 | kuning (lewat pembagi 1k/2k) |
| Sensor VCC / GND | VIN (5V) / GND | | putih,merah / hitam,cokelat |

Tidak ada pin yang dipakai ganda.
Catatan: D4 (GPIO2) harus HIGH saat boot, modul relay aktif LOW biasanya aman. Kalau relay bunyi saat boot, pindah ke D3 dan sesuaikan PIN_RELAY.
