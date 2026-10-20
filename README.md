# Kontrol Pompa dan Monitor 2 Toren

Aplikasi web untuk mengontrol pompa air dan memantau ketinggian air di dua toren, dengan alat berbasis NodeMCU ESP8266 dan dua sensor ultrasonik HC-SR04. Data dan perintah lewat Firebase Realtime Database.

## Isi repositori

| Lokasi | Isi |
|---|---|
| `index.html`, `sw.js`, `manifest.json`, ikon | Aplikasi web (dipublikasikan lewat GitHub Pages) |
| `hardware/kontrol_pompa/kontrol_pompa.ino` | Firmware ESP8266 (Arduino IDE) |
| `hardware/skema-pengkabelan.svg` dan `.png` | Skema pengkabelan lengkap |
| `hardware/PIN_MAPPING.md` | Tabel pin dan warna kabel |
| `hardware/wokwi/` | Berkas simulasi Wokwi (versi ESP32) |

## Fitur

- Pompa bisa dinyalakan dari aplikasi (dengan timer) atau saklar fisik (20 menit). Saklar OFF langsung mematikan pompa.
- Status pompa tampil langsung di aplikasi.
- Persen dan volume dua toren dari kalibrasi di aplikasi.
- Status alat terhubung atau terputus, nama WiFi, dan kekuatan sinyal.
- Riwayat pompa menyala: ringkasan, grafik, dan daftar, per hari, minggu, bulan, dan tahun.
- Tiga WiFi dengan pemilihan sinyal terkuat, mode offline, watchdog, reboot harian, dan pembaruan lewat WiFi (OTA).
- Layar LCD I2C 16x2 dengan animasi, status pompa, timer, persen toren, dan WiFi.

## Cara memakai

1. **Firebase:** buat Realtime Database. Alamat database ditulis di `index.html` (konstanta `DB_URL`) dan di firmware (`DB_HOST`).
2. **Firmware:** buka `hardware/kontrol_pompa/kontrol_pompa.ino` di Arduino IDE. Isi `FOLDER`, `WIFI_LIST`, dan `OTA_PASSWORD` yang masih berupa teks isian. Pasang pustaka `ArduinoJson` dan `LiquidCrystal I2C`, pilih board NodeMCU 1.0 (ESP-12E), lalu upload.
3. **Perangkat keras:** ikuti `hardware/skema-pengkabelan.svg` dan `hardware/PIN_MAPPING.md`.
4. **Aplikasi:** aktifkan GitHub Pages dari cabang `main` (Settings, Pages). Buka alamat Pages, lalu masukkan kode akses.

## Data rahasia

Repositori ini publik. Nama folder rahasia Firebase, sandi WiFi, dan sandi OTA sengaja **tidak** disertakan dan diganti teks isian. Isi di salinan lokal firmware dan jangan unggah salinan yang sudah terisi.
