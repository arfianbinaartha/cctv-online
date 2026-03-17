# CCTV (Android + Web)

Proyek contoh CCTV sederhana: aplikasi Android mengirim video WebRTC, server Node.js jadi signaling + kontrol, halaman web menampilkan stream dan tombol kendali (start/stop).

## Struktur
- `android-app/` — aplikasi Android (Kotlin, Camera2 + Google WebRTC).
- `server/` — server signaling (Express + Socket.IO) sekaligus menyajikan UI web.
- `server/public/index.html` — UI web untuk melihat video & kirim perintah.

## Cara jalan cepat
1) Server
```bash
cd server
npm install
npm start
# server default: http://localhost:3000
```
2) Android
- Buka `android-app` di Android Studio.
- Sync Gradle, pasang di perangkat fisik (perlu kamera & internet).
- Ubah `SIGNALING_URL` di `MainActivity.kt` kalau server tidak di localhost (gunakan IP publik/LAN).

3) Web Viewer
- Buka `http://<server-host>:3000` di browser desktop/mobile.
- Tekan **Request Stream** untuk membuat offer, Android akan menjawab dan mengirim video.
- Tekan **Stop Stream** untuk kirim perintah henti.

## Catatan
- Ini kerangka minimal; silakan amankan (auth/JWT, HTTPS, STUN/TURN produksi).
- WebRTC memerlukan STUN/TURN; contoh memakai server Google STUN gratis.
- Untuk internet publik, pastikan port 3000 terbuka atau pakai reverse proxy.
- Production: pindahkan file statis ke CDN, tambahkan logging, monitoring, dan perintah tambahan (ambil foto, ubah kamera, resolusi, dll).
