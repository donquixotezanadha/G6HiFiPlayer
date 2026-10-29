# Build 2.8 — catatan perubahan

Branch: `claude-build-208-player` (dari Build 2.7). Versi 2.8.0, versionCode 208, artifact `G6HiFiPlayer-Build208-v2.8.0`.
Suara/jalur DIRECT tidak diubah.

## Perubahan
1. Tab Album: jika baris terakhir hanya berisi 1 album (atau kurang dari jumlah kolom), sel kosong dengan bobot sama
   mengisi sisa baris, jadi cover tetap seukuran saat 2 atau 3 kolom dan berada di kiri (tidak melebar penuh).
2. Kontrol Now Playing: ikon teks (⏮ ⏭) dan kotak ungu diganti tombol ikon putih polos, sedikit lebih besar
   (prev/next 52dp, play/pause 60dp; ikon prev/next ±28dp, play/pause ±40dp).
3. Animasi:
   - Album art Now Playing: saat pause mengecil ke 88%, saat play membesar kembali dengan sedikit membal (overshoot).
     Pada saat app dibuka, kondisi awal langsung dipasang tanpa animasi.
   - Tombol prev/play-pause/next: ripple putih pendar (bulat) dan efek tekan kecil (mengecil lalu membal).
4. Tombol Back di halaman Player tidak lagi menghentikan app: sekarang `moveTaskToBack(true)` (seperti Home),
   musik tetap jalan. Menutup total tetap lewat tombol "Close player" di notifikasi.
   Back dari antrean/album/tab lain tetap kembali satu langkah seperti sebelumnya.

## Uji di HP
(a) Tab Album dengan 1 album di baris terakhir: cover sama besar dengan yang lain, di kiri.
(b) Tombol kontrol putih, agak besar; ripple putih saat disentuh.
(c) Pause: cover mengecil; Play: cover membesar membal. Cek juga saat ganti lagu.
(d) Tekan Back di halaman Player: app ke latar belakang, musik tetap jalan, notifikasi tetap ada.
