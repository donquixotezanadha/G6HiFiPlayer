# Build 2.1 — catatan perubahan

Branch: `claude-build-201-player` (dari Build 2.0). Versi 2.1.0, versionCode 201, artifact `G6HiFiPlayer-Build201-v2.1.0`.
Suara/jalur DIRECT tidak diubah. Notifikasi sengaja tidak disentuh (ditunda).

## Perubahan
1. Setting: kotak gelap di belakang tombol dihapus (tombol langsung di atas latar halaman, sejajar dengan judul seksi).
2. Now Playing: cover jadi 80% lebar layar (konstanta `ART_WIDTH_FRACTION`).
3. Now Playing: judul, progress, dan kontrol lebih ke bawah (kolom menempel ke bawah, jarak cover ke judul 28dp). Jika layar terlalu pendek, cover mengecil otomatis.

## Uji di HP
(a) Setting: tidak ada kotak gelap di belakang tombol.
(b) Now Playing: cover lebih besar, judul dan kontrol lebih turun, info BIT-PERFECT tidak tertabrak bar bawah.
