# Build 2.10 — catatan perubahan

Branch: `claude-build-210-player` (dari Build 2.9). Versi 2.10.0, versionCode 210, artifact `G6HiFiPlayer-Build210-v2.10.0`.
Suara/jalur DIRECT tidak diubah. Hanya tampilan.

## Perubahan
1. Warna aksen -> abu-abu (`ACTION_GREY`):
   - tombol "x" di daftar Queue abu-abu, kecuali pada track yang sedang terpilih (tetap warna aksen); ikut berubah saat lagu ganti
     (lewat `AlbumRowMark.action` di `updateAlbumPlayingMarks`).
   - tombol "+" di daftar lagu album (kedua daftar) abu-abu.
2. Tombol prev/play/next lebih besar: prev/next 64dp -> 80dp (ikon ~56dp), play 78dp -> 96dp (ikon ~76dp).
3. Jarak antara deskripsi (judul/artis/album) dan progress control dikurangi: `PROGRESS_TOP_GAP_DP` 14dp -> 2dp.
   Ruang yang dibebaskan otomatis dipakai cover (ukuran cover dihitung dari tinggi dock).

## Uji di HP
(a) "x" Queue abu-abu, track terpilih tetap ungu; "+" album abu-abu.
(b) Tombol kontrol terasa lebih besar, tetap seimbang.
(c) Jarak album -> waktu/progress lebih rapat.
