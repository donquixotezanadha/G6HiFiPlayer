# Build 1.56 — Engine Lab: route matrix (dasar: dump Build 1.38)

## Temuan dump 1.38 (26 Okt 2026)
- Track app (pid 18565, session 385): format 0x6 (PCM_24_BIT_PACKED), 96000 Hz, flags 0x000.
- Track itu masuk thread AudioOut_15: type MIXER, flag DEEP_BUFFER, sample rate 48000, HAL format 16-bit, WIRED_HEADPHONE. => di-resample 96k->48k dan dipotong 16-bit. BUKAN DIRECT, BUKAN bit-perfect.
- Efek "Volume listener for Music" menempel di session 385. Underruns track = 3844 frame.
- Tidak ada thread DIRECT sama sekali saat dump (hanya primary/fast speaker, deep buffer headphone, dan thread kosong).
- Artinya: OpenSL ES berhasil membuka 96k/24-bit, tetapi policy audio G6 menaruh track flags-NONE di deep-buffer mixer. Format/rate yang sama dengan UAPP tidak otomatis memicu DIRECT.

## Yang berubah di 1.56
- g6sles.cpp: nativeOpenEx(rate, channels, bits 16|24, perfMode 0..4). perfMode 0 = tanpa konfigurasi (default framework), 1 NONE, 2 POWER_SAVING, 3 LATENCY, 4 LATENCY_EFFECTS.
- RouteMatrix.kt + tombol "3 · Run route matrix" di Engine Lab: memutar nada 1 kHz pelan lewat 15 varian, 8 detik tiap varian (tanpa file audio):
  A1-A3 SLES 16-bit 44.1/48/96k; B1-B4 SLES 24-bit 44.1/48/96/192k; C1-C4 SLES 96k/24-bit dengan perf 0/2/3/4; D1-D4 Java AudioTrack 96k (PCM16, float, float+POWER_SAVING, PCM16+FLAG_HW_AV_SYNC).
- Log: MATRIX_VARIANT id=.. START/END (waktu epoch ms), SLES_OPEN / JAVA_OPEN.

## Cara uji (komputer, adb)
1. Colok HP, buka Engine Lab 1.56, tekan "3 · Run route matrix" dan JANGAN tutup layarnya.
2. Segera di komputer (Command Prompt Windows):
   adb shell "for i in $(seq 1 90); do echo ==== $(date +%s); dumpsys media.audio_flinger; sleep 2; done" > matrix_dump.txt
   (Mac/Linux: ganti tanda kutip ganda luar menjadi kutip tunggal. PowerShell: pakai kutip tunggal.)
3. Setelah selesai (~3 menit), di Engine Lab tekan COPY DIAGNOSTICS, lalu kirim matrix_dump.txt + teks diagnostik.
Yang dicari: baris "Output thread ... (DIRECT)" atau sample rate/HAL format selain 48000/PCM_16 saat varian tertentu aktif.

## Belum diketahui
- Apakah ada varian publik API yang membuat policy memilih DIRECT. Jika tidak ada satu pun, langkah berikut harus memakai perilaku UAPP (butuh dump UAPP yang membandingkan AudioAttributes/session), bukan menebak lagi.
