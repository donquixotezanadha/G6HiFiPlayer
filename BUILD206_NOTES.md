# Build 2.6 — catatan perubahan

Branch: `claude-build-206-player` (dari Build 2.5). Versi 2.6.0, versionCode 206, artifact `G6HiFiPlayer-Build206-v2.6.0`.
Suara/jalur DIRECT tidak diubah.

## Perubahan
1. Album art dan control panel diperbesar sedikit (di antara Build 2.4 dan 2.5):
   - cover = selebar progress bar (`ART_WIDTH_FRACTION` 0.86 -> 1.0; lebar bar kini lebih pendek, lihat nomor 2)
   - tombol play 62x46 -> 68x50dp, prev/next 40 -> 44dp, ikon 17 -> 18sp
2. Progress bar dan judul sama lebar: semua baris di dock Now Playing (judul/artis/album, waktu, progress, kontrol, dua baris info)
   memakai satu konstanta padding kiri-kanan `SEEK_SIDE_PAD_DP` 18 -> 30dp, jadi diperpendek kanan-kiri dan seimbang.
3. Gap list queue dan list lagu album: padding vertikal baris 3 -> 5dp (sedikit saja).

## Uji di HP
(a) Now Playing: cover, judul, progress, dan kontrol sejajar kiri-kanan; kontrol sedikit lebih besar dari 2.5 tapi tidak sebesar 2.4.
(b) Swipe ke bawah (queue) dan buka album: jarak antar baris sedikit lebih lega.
