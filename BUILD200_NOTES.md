# Build 2.0 — catatan perubahan

Branch: `claude-build-200-player` (dari Build 1.67). Versi 2.0.0, versionCode 200, artifact `G6HiFiPlayer-Build200-v2.0.0`.
Suara/jalur DIRECT native 24-bit packed TIDAK diubah.

## Perubahan
1. Tema Adaptive dihapus dari kode. Versi lengkapnya tetap ada di branch `claude-build-167-player` (arsip).
2. Hanya satu tema: **Dark** (bekas Classic). Setting punya tombol DARK (aktif) dan LIGHT (placeholder, belum ada isinya; menekannya hanya memberi pesan).
3. Cover Now Playing diperkecil (sekitar 62% lebar layar). Judul, progress, dan kontrol langsung di bawah cover, satu kolom di tengah layar.
4. Setting: kotak gelap di belakang deskripsi dihapus; kotak gelap hanya tersisa di area tombol (pilih folder, DARK/LIGHT, 2/3 kolom).
5. Notifikasi: latar hitam tetap (setColor hitam + colorized), teks dan kontrol putih.
6. Info audio (dua baris, masing-masing satu baris, bergeser otomatis/marquee jika terlalu panjang):
   - Baris 1: jenis file + sumber → output, mis. `FLAC • 96.0 kHz / 24-bit → 96.0 kHz`
   - Baris 2: status + headset/speaker + catatan, mis. `● BIT-PERFECT • headset • real 24-bit`
7. Versi 2.0.

## Uji di HP
(a) Now Playing: cover kecil, judul dan kontrol tepat di bawahnya.
(b) Baris info: jenis file benar (FLAC/WAV/M4A), baris 2 hijau BIT-PERFECT dengan kata headset.
(c) Setting: tidak ada kotak gelap di deskripsi; tombol LIGHT hanya menampilkan pesan.
(d) Notifikasi: hitam, teks dan tombol putih (kemungkinan perlu dicek karena tampilan notifikasi dikendalikan sistem).
