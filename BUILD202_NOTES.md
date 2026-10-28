# Build 2.2 — catatan perubahan

Branch: `claude-build-202-player` (dari Build 2.1). Versi 2.2.0, versionCode 202, artifact `G6HiFiPlayer-Build202-v2.2.0`.
Suara/jalur DIRECT dan notifikasi tidak diubah.

## Perubahan
1. Cache cover album di disk: setiap cover grid (320 px, JPEG) disimpan di `cache/thumbs`. Saat aplikasi dibuka, cover semua album dibaca dari disk ke memori SEBELUM grid digambar, jadi grid langsung penuh tanpa loading. Cover yang memang tidak ada ditandai (`.none`) supaya file tidak dibuka lagi tiap start.
2. Scan otomatis di background: setelah daftar tersimpan tampil, folder dibaca diam-diam (tanpa pindah halaman, tanpa progress bar). File yang sudah dikenal memakai tag tersimpan (tidak dibaca ulang), jadi cepat. Daftar hanya diperbarui kalau ada lagu baru/hilang/cover berubah, lalu cache disimpan lagi. Scan yang gagal atau kosong tidak menimpa daftar lama.
3. Kembali ke aplikasi (onStart) juga memeriksa ulang, paling cepat tiap 1 menit.
4. Scan manual (Choose Music Folder) tetap seperti biasa; hasil scan background yang lebih tua dibuang (scanGeneration).

## Uji di HP
(a) Buka pertama setelah pasang: cover dibuat dan disimpan (boleh sedikit muncul bertahap).
(b) Tutup total lalu buka lagi: grid album langsung penuh dengan cover, tanpa loading.
(c) Tambah/hapus lagu di folder, buka aplikasi: jumlah track ikut berubah sendiri tanpa menekan scan.
