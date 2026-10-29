# Build 2.12 — catatan perubahan

Branch: `claude-build-212-player` (dari Build 2.11). Versi 2.12.0, versionCode 212, artifact `G6HiFiPlayer-Build212-v2.12.0`.
Suara/jalur DIRECT tidak diubah. Hanya tampilan Now Playing. Posisi semua tombol tidak diubah (area sentuh tetap 52dp / 60dp).

## Perubahan
1. Ikon play/pause diperkecil ke tengah-tengah antara 2.8 dan 2.11: 2.8 = ikon 24dp, 2.11 = ikon 60dp (memenuhi area), 2.12 = 42dp.
   Caranya: area tombol tetap 60dp, ditambah padding 9dp (`PLAY_ICON_PAD_DP`) di sekeliling ikon, jadi titik tengahnya tidak bergeser.
   Ikon prev/next tidak diubah (tetap seperti 2.11).
2. Efek glow tidak terlihat di 2.11 karena ikon memenuhi seluruh area tombol dan gradasi glow memudar sebelum keluar dari ikon.
   Sekarang (`GlowDrawable`):
   - jari-jari glow = setengah tombol + 5dp (`GLOW_EXTRA_DP`), jadi cahaya terlihat di sekeliling ikon (baris tombol punya sisa 6dp atas/bawah, tidak terpotong);
   - cahaya lebih terang (puncak alpha 0x80, dataran sampai 55% jari-jari, lalu memudar halus ke transparan, tanpa tepi lingkaran);
   - saat dilepas, cahaya naik dulu ke minimal 85% lalu memudar pelan 520 ms (`fadeOut`), sehingga ketukan cepat tetap terlihat.

## Uji di HP
(a) Ikon play/pause lebih kecil dari 2.11, lebih besar dari 2.8; posisinya sama seperti sekarang.
(b) Sentuh/ketuk play, prev, next: ada pendar cahaya lembut yang jelas terlihat dan sebesar tombol.
