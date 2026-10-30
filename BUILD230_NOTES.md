# Build 2.30 — catatan perubahan

Branch: `claude-build-230-player` (dari **Build 2.17**, bukan dari 2.18/2.19). Versi 2.30.0, versionCode 230, artifact `G6HiFiPlayer-Build230-v2.30.0`.
Suara/jalur DIRECT tidak diubah. Build 2.18 dan 2.19 dilewati (salah paham: semua album art diganti gambar Hi-Res).

## Perubahan (hanya ini)
Badge teks "Hi-Res" buatan 2.16 diganti **gambar logo Hi-Res Audio kecil** di pojok kanan bawah cover, seperti di UAPP.
- Hanya muncul pada track **24-bit** (aturan sama seperti 2.16/2.17: `sourceBits >= 24`). Track 16-bit tidak berubah.
- Album art tetap album art asli. Tidak ada cover yang diganti.
- Tempat badge sama seperti 2.17: Now Playing, mini player, notifikasi/lock screen, widget 4x2 dan 2x2 (di 2x2 bulat, logo digeser ke dalam supaya tidak terpotong).
- Ukuran logo sekitar 20% lebar cover (lebih kecil dari badge teks lama yang 30%).
- Gambar: `res/drawable-nodpi/hires_logo.png` (192 px, diambil dari logo kiriman pengguna di 2.18). `HiResBadge.kt` kini menggambar bitmap ini, bukan teks.

## Uji di HP
(a) Putar track 24-bit: logo Hi-Res kecil di pojok kanan bawah cover Now Playing dan mini player.
(b) Putar track 16-bit: tidak ada logo.
(c) Notifikasi dan widget (track 24-bit): logo kecil di pojok kanan bawah cover.
