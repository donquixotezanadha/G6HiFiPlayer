# Build 2.15 — catatan perubahan

Branch: `claude-build-215-player` (dari Build 2.14). Versi 2.15.0, versionCode 215, artifact `G6HiFiPlayer-Build215-v2.15.0`.
Suara/jalur DIRECT tidak diubah.

## 1. Ukuran glow sentuh tombol: 50%
Jari-jari glow pada tombol prev/play/next dikalikan **0.5** (`GLOW_SIZE_SCALE`), jadi diameter cahaya tinggal setengahnya.
Opasitas (30%), bentuk gradien, dan animasi (muncul 120 ms, memudar 520 ms) tidak diubah dari 2.14. Posisi dan ukuran tombol/ikon tidak diubah.
Perubahan hanya di `GlowDrawable.radius()` pada `MainActivity.kt`.

## Uji di HP
(a) Glow di tombol prev/play/next kira-kira setengah ukuran 2.14 (lebih kecil, tetap lembut, tanpa lingkaran keras).
(b) Widget dan bagian lain sama seperti 2.14.
