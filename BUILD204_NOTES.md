# Build 2.4 — catatan perubahan

Branch: `claude-build-204-player` (dari Build 2.3). Versi 2.4.0, versionCode 204, artifact `G6HiFiPlayer-Build204-v2.4.0`.
Suara/jalur DIRECT tidak diubah.

## Perubahan
1. Notifikasi "Exclusive mode" (takeover) hilang otomatis saat Close ditekan (tombol notifikasi, tombol back di Now Playing,
   atau aplikasi digeser dari recent saat tidak ada lagu yang diputar). Penyebab: aksi Close dari dalam aplikasi
   memakai `ACTION_STOP` yang masih meninggalkan notifikasi standby. Sekarang `ACTION_STOP` = tutup total.
   Takeover aktif lagi hanya saat aplikasi dibuka kembali (`armExclusive` di `onStart`, tidak dijalankan kalau activity sedang menutup).
2. Tab Album (grid luar): cover kini persegi, `CENTER_CROP`, sudut membulat 10dp dengan clip outline eksplisit
   (sebelumnya `FIT_CENTER` tanpa clip, jadi sudut tidak bulat). Cover halaman album dan baris "More albums" memakai clip yang sama.
3. Daftar lagu di halaman album: baris abu-abu di bawah judul berisi jenis file (FLAC/WAV/M4A, dari nama file), tahun dari ID tag
   (MP3/FLAC/M4A lewat MediaMetadataRetriever, WAV lewat ID3 TYER/TDRC atau INFO ICRD), dan artis bila beda dari artis album.
   Gap antar baris dikecilkan (padding 8dp -> 3dp, margin bawah dihapus, tombol "+" 44dp -> 36dp).
   Lagu yang sedang diputar: judul dan nomor berwarna aksen, latar sorot, dan 3 batang equalizer kecil di kanan
   yang bergerak saat play dan diam saat pause (`EqualizerView.kt`); ikut berubah saat lagu berganti.
   Tahun belum ada di library lama: scan latar belakang membaca ulang tag sekali (library tampil dari cache seperti biasa).
4. Now Playing: keterangan "16-bit padded to 24" di sebelah BIT-PERFECT dihapus.

## Uji di HP
(a) Tekan Close di notifikasi, juga back dari Now Playing: notifikasi "Exclusive mode" ikut hilang; buka app lagi dengan headset: muncul lagi.
(b) Tab Album: cover bersudut bulat (2 dan 3 kolom).
(c) Buka album: tiap lagu punya baris "FLAC • tahun"; lagu yang diputar berwarna dengan bar bergerak di kanan; gap antar lagu rapat.
(d) File 16-bit: tidak ada teks "16-bit padded".
