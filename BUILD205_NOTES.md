# Build 2.5 — catatan perubahan

Branch: `claude-build-205-player` (dari Build 2.4). Versi 2.5.0, versionCode 205, artifact `G6HiFiPlayer-Build205-v2.5.0`.
Suara/jalur DIRECT tidak diubah.

## Perubahan
1. Queue: daftar kini memakai baris yang sama dengan daftar lagu di halaman album (ukuran, jarak antar baris 3dp, animasi):
   tanpa album art, hanya teks: nomor, judul, baris abu-abu (jenis file • tahun • artis), lagu yang diputar berwarna aksen
   dengan latar sorot dan 3 batang equalizer di kanan (bergerak saat play, diam saat pause). Tombol "+" diganti "×" untuk
   menghapus dari queue (lagu yang sedang diputar tidak bisa dihapus, seperti sebelumnya).
2. Now Playing lebih compact: cover 86% dari lebar progress bar (konstanta `ART_WIDTH_FRACTION`), judul 20 -> 17sp,
   artis 15 -> 13sp, album 13 -> 11sp, tombol play 76x56 -> 62x46dp, prev/next 48 -> 40dp dengan ikon 20 -> 17sp.

## Uji di HP
(a) Geser Now Playing ke bawah: queue tampil sebagai list teks; lagu yang main berwarna + bar bergerak; "×" menghapus lagu.
(b) Now Playing terasa lebih lega; cover, judul, dan kontrol tetap rapi dan tidak terpotong.
