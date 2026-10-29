# Build 2.9 — catatan perubahan

Branch: `claude-build-209-player` (dari Build 2.8). Versi 2.9.0, versionCode 209, artifact `G6HiFiPlayer-Build209-v2.9.0`.
Suara/jalur DIRECT tidak diubah. Hanya tampilan halaman Now Playing.

## Perubahan
1. Progress bar Now Playing lebih tipis: 4dp -> 3dp (`PROGRESS_THICKNESS_DP`).
2. Tombol prev/play/next lebih besar: prev/next 52dp -> 64dp (ikon ~38dp), play/pause 60dp -> 78dp (ikon ~54dp)
   (`TRANSPORT_SIDE_DP`, `TRANSPORT_PLAY_DP`).
3. Animasi cover play/pause hanya sedikit: saat pause mengecil ke 95% (sebelumnya 88%), saat play kembali ke tepat 100%
   tanpa overshoot, jadi tidak pernah lebih lebar dari progress bar (`ART_PAUSED_SCALE`).
4. Teks "real 24-bit" di baris status dihapus (sudah terwakili BIT-PERFECT). Catatan "low 8 bits empty" tidak diubah.
5. Ripple lingkaran pada tombol prev/play/next diganti cahaya lembut (`GlowDrawable`): gradasi putih dari tengah yang
   memudar ke transparan (tanpa tepi lingkaran), muncul cepat saat disentuh dan memudar pelan saat dilepas;
   efek tekan kecil (mengecil ke 93%, kembali halus tanpa membal).

## Uji di HP
(a) Progress bar lebih tipis dari 2.8, thumb putih tetap terlihat.
(b) Tombol kontrol lebih besar, tetap seimbang; cover tidak terjepit.
(c) Pause: cover mengecil sedikit; Play: kembali, tidak melewati lebar progress bar.
(d) Baris status tidak lagi menulis "real 24-bit".
(e) Sentuh tombol: ada pendar cahaya lembut, bukan lingkaran yang menyebar.
Tab dan daftar lain masih memakai efek sentuh bawaan (persegi membulat); belum diubah.
