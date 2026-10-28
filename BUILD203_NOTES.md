# Build 2.3 — catatan perubahan

Branch: `claude-build-203-player` (dari Build 2.2). Versi 2.3.0, versionCode 203, artifact `G6HiFiPlayer-Build203-v2.3.0`.
Suara/jalur DIRECT, cache cover dan scan tidak diubah.

## Perubahan
1. Now Playing: cover selebar progress bar (lebar layar dikurangi 2 x 18dp). Judul + progress + kontrol turun lagi
   (padding bawah kolom 20dp -> 8dp, dock 8dp -> 4dp). Cover tepat di tengah antara status bar dan judul
   (jarak atas = jarak bawah, dihitung ulang di `sizeDarkArt`). Di layar pendek cover mengecil otomatis.
2. Halaman album yang dibuka: cover di tengah (66% lebar layar), judul album + artis, PLAY ALBUM / ADD TO QUEUE,
   lagu berupa daftar bernomor tanpa gambar, lalu "More albums by <artis>" yang bisa digeser ke kiri
   (HorizontalScrollView). Tap album di baris itu membuka album tersebut dan halaman kembali ke atas.
3. Notifikasi: kode warna hitam (`setColor(BLACK)` + `setColorized(true)`) dihapus dari notifikasi pemutar
   dan notifikasi "Exclusive mode". Tombol "Close player" di kedua notifikasi sekarang memakai aksi baru
   `ACTION_EXIT` yang benar-benar menutup: musik berhenti, notifikasi hilang, service berhenti, takeover dilepas.
   Penyebab sebelumnya: Close hanya menghentikan musik lalu service memasang lagi notifikasi "Exclusive mode"
   (karena mode exclusive selalu on), jadi tidak bisa ditutup. Setelah Close, takeover aktif lagi saat
   aplikasi dibuka kembali (dan jack terpasang).

## Uji di HP
(a) Now Playing: lebar cover sama dengan progress bar, jarak status bar -> cover sama dengan cover -> judul.
(b) Buka album: cover di tengah, lagu bernomor, "More albums" muncul kalau artisnya punya album lain dan bisa digeser.
(c) Notifikasi tidak lagi berwarna hitam; tombol X di notifikasi "Exclusive mode" menghilangkan notifikasi itu.
