# Build 1.67 — rencana dan catatan perubahan

Branch: `claude-build-167-player` (dari Build 1.66). Ditulis 28 Okt 2026 karena token pengguna hampir habis.

## STATUS
Kode sudah diubah di branch ini (MainActivity.kt, PlaybackKeepAliveService.kt), versi 1.67.0 (versionCode 167), workflow `build-167-claude.yml`.
BELUM dikompilasi dan BELUM diuji di HP. Langkah berikutnya: cek hasil GitHub Actions, perbaiki bila gagal build, lalu uji di HP.
Artifact: `G6HiFiPlayer-Build167-v1.67.0`.

## Permintaan pengguna (review Build 1.66)

### 1. Baris info: 1 baris, baris kedua tetap status
Masalah di 1.66 (lihat screenshot): teks format terlalu panjang (`WAV • 1413 kbps • 44.1 kHz / 16-bit → 44.1 kHz • PCM 16-bit padded to 24-bit`)
jadi 2 baris, lalu baris status (hijau BIT-PERFECT) terpotong oleh `maxLines = 2`.
Yang diminta:
- Baris 1 hanya `sumber → output` + `by headset` atau `by speaker`, FIX 1 baris. Contoh: `44.1 kHz / 16-bit → 44.1 kHz • by headset`.
  Tanpa format file (WAV/FLAC), tanpa bitrate, tanpa teks "PCM ... padded".
- Baris 2 selalu status: `● BIT-PERFECT` (hijau) + catatan abu-abu singkat (`16-bit padded to 24` / `real 24-bit` / `low 8 bits empty`).
Yang diubah di kode: `MainActivity.refreshAudioInfo()` ditulis ulang; fungsi baru `fitAudioInfoLines()` mengecilkan huruf (9 sp ke 6 sp) bila baris masih lebih lebar dari layar, jadi tetap 1 baris per baris.
Headset/speaker dibaca dari `wiredJackConnected()`.

### 2. Exclusive mode otomatis (default), tanpa notifikasi konfirmasi dan tanpa opsi
Masalah di 1.66: notifikasi "Take over / Not now" hilang begitu ditekan, jadi pengguna tidak tahu apakah takeover aktif atau tidak.
Yang diminta:
- Jadikan default, tanpa notifikasi konfirmasi, tanpa opsi di Setting.
- Takeover OFF hanya ketika headset dicabut; selain itu selalu ON.
Yang diubah di kode:
- `MainActivity`: kartu "EXCLUSIVE MODE" di Setting dan fungsi `fillExclusiveButtons` / `setExclusiveMode` dihapus.
- `PlaybackKeepAliveService`: notifikasi "Take over?" dihapus. Jack dicolok, atau aplikasi dibuka saat jack sudah terpasang, langsung `autoTakeover()` (`ExclusiveAudioFocus.startTakeover`).
  `exclusiveEnabled()` selalu `true`. Jack dicabut tetap memanggil `stopTakeover()` (sudah ada di 1.66).
  Notifikasi standby hanya info, tombol Take over / Release dihapus.
- Catatan: tombol "Close player" melepas takeover; membuka aplikasi lagi dengan jack terpasang mengaktifkannya kembali.

## Uji di HP
(a) Lagu 16-bit/44.1 dan 24-bit/96: baris 1 satu baris `... • by headset`, baris 2 hijau BIT-PERFECT.
(b) Colok jack: tidak ada notifikasi tanya; buka YouTube atau bunyikan notifikasi: tidak mengganggu. Cabut jack: takeover mati.
(c) Setting: tidak ada lagi opsi Exclusive mode.
(d) Tanpa headset (speaker): baris 1 `by speaker`, status oranye `SPEAKER • NOT BIT-PERFECT`.

## Belum dikerjakan (dari catatan 1.66)
Posisi dock tema Classic vs Adaptive masih perlu dibandingkan lewat log `DOCK_LAYOUT` dari diagnostics.

## Jangan diubah
Jalur DIRECT native 24-bit packed (terbukti di dump) jangan disentuh.
