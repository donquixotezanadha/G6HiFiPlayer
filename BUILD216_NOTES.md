# Build 2.16 — catatan perubahan

Branch: `claude-build-216-player` (dari Build 2.15). Versi 2.16.0, versionCode 216, artifact `G6HiFiPlayer-Build216-v2.16.0`.
Suara/jalur DIRECT tidak diubah.

## 1. Glow ripple 75%
`GLOW_SIZE_SCALE` 0.5 -> 0.75 (MainActivity.kt). Opasitas 30%, bentuk, animasi, posisi/ukuran tombol tidak berubah.

## 2. Logo Hi-Res di pojok kanan bawah album art (musik 24-bit)
Berlaku saat lagu yang diputar 24-bit atau lebih (`sourceBits >= 24`, dibaca dari header file):
- cover besar di Now Playing,
- cover mini player,
- cover di notifikasi / lock screen / widget (bitmap diberi badge, di-cache terpisah dengan kunci `|hires`).
Badge digambar sendiri (`HiResBadgeDrawable`): plat gelap, tulisan "Hi-Res" emas. Bukan logo resmi Japan Audio Society.
Hilang otomatis untuk lagu 16-bit.

## Uji di HP
(a) Putar FLAC 24-bit: badge Hi-Res muncul di pojok kanan bawah cover Now Playing, mini player, notifikasi.
(b) Putar 16-bit: badge tidak ada.
(c) Glow tombol prev/play/next lebih besar dari 2.15 (75%).
