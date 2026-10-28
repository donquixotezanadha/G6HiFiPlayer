# Build 1.67 — catatan perubahan

Branch: `claude-build-167-player` (dari Build 1.66). 28 Okt 2026.

1. **Baris info 1 baris + baris status.** Baris 1 hanya `sumber → output • by headset` (atau `by speaker`), contoh `44.1 kHz / 16-bit → 44.1 kHz • by headset`.
   Tanpa format file dan bitrate. Baris 2 = status (hijau `● BIT-PERFECT` + catatan abu-abu `16-bit padded to 24` / `real 24-bit` / `low 8 bits empty`).
   Penyebab status hilang di 1.66: baris 1 terlalu panjang jadi 2 baris, baris status terpotong oleh `maxLines = 2`.
   Bila baris masih lebih lebar dari layar, ukuran huruf mengecil otomatis (9 sp sampai 6 sp) supaya tetap 1 baris.
2. **Exclusive mode otomatis, tanpa opsi.** Opsi di Setting dihapus. Tidak ada notifikasi "Take over?".
   Jack dicolok (atau aplikasi dibuka saat jack sudah terpasang) → langsung ambil alih audio focus.
   Takeover mati hanya saat jack dicabut; selain itu selalu ON. Notifikasi standby hanya info (tanpa tombol Take over/Release).
   Catatan: tombol "Close player" melepas takeover; membuka aplikasi lagi dengan jack terpasang mengaktifkannya kembali.
3. Versi 1.67.0 (versionCode 167), workflow `build-167-claude.yml`, artifact `G6HiFiPlayer-Build167-v1.67.0`.
