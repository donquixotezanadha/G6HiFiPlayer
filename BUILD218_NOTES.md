# Build 2.18 — catatan perubahan

Branch: `claude-build-218-player` (dari Build 2.17). Versi 2.18.0, versionCode 218, artifact `G6HiFiPlayer-Build218-v2.18.0`.
Suara/jalur DIRECT tidak diubah.

## 1. Semua album art = gambar Hi-Res Audio
Gambar dari pengguna dipotong persegi (hanya logo, tanpa tepi putih) dan disimpan di `res/drawable-nodpi/hires_cover.png` (768 px).
`readArtworkBytes()` sekarang selalu memberi gambar ini, jadi semua tempat di dalam player ikut: Now Playing, mini player, daftar album/grid, halaman album, "More albums", antrean, warna latar (dari gambar ini).
Cache cover di disk lama diabaikan (`ART_CACHE_TAG`), cache library tidak diubah (tidak perlu scan ulang).
Badge Hi-Res buatan 2.16 dihapus (`HiResBadge.kt` dihapus) karena covernya sudah logo Hi-Res.

## 2. Tanpa album art di luar player
- Notifikasi: tidak ada large icon.
- Lock screen: MediaSession tidak lagi diberi bitmap (ART / ALBUM_ART / DISPLAY_ICON), jadi lock screen tanpa gambar.
- Widget 4x2 dan 2x2: gambar dihapus. 4x2 = teks + progress + tombol di kartu hitam 80%; 2x2 = kartu hitam 80% membulat dengan tombol play di tengah.

## Uji di HP
(a) Semua cover di dalam player berupa logo Hi-Res (Now Playing, mini player, grid album, halaman album).
(b) Notifikasi dan lock screen tanpa gambar.
(c) Widget tanpa gambar (hapus lalu tambahkan lagi bila tampilan lama masih tersisa).
