# Build 1.66 — catatan perubahan (BELUM ADA KODE YANG DIUBAH)

Branch: `claude-build-166-player` (dari `claude-build-165-player`, Build 1.65).
Ditulis 28 Okt 2026 karena token pengguna habis. Isi di bawah adalah rencana + hasil membaca kode 1.65, belum diukur di HP.

## Perubahan 1 — Label bit-perfect harus sama dengan hasil dump

**Laporan pengguna:** satu lagu (ONE OK ROCK "When They Turn the Lights On", FLAC 96 kHz / 24-bit)
masih menampilkan label oranye `● 24-BIT CARRIER, BUT LOW 8 BITS ARE EMPTY`,
padahal `dumpsys media.audio_flinger` lagu 96 kHz itu menunjukkan bit-perfect.

**Bukti dump (Build 1.65, 4 file, semuanya sama):**
Thread type 1 (DIRECT), flags `AUDIO_OUTPUT_FLAG_DIRECT`, device `0x8 WIRED_HEADPHONE`,
HAL format `0x6 PCM_24_BIT_PACKED`, track 1 aktif, `L dB 0 R dB 0`, tanpa SRC / EQ / mixer,
satu-satunya efek = "Volume listener" Qualcomm (bukan efek audio). Rate sesuai sumber: 44.1 / 88.2 / 96 / 96 kHz.
File `dump_24bit96khzgagal.txt` namanya "gagal" tetapi isinya sukses (identik dengan dump 96 kHz).

**Yang ditemukan di kode 1.65** (`MainActivity.refreshAudioInfo()`, baris ±2338):
label oranye itu bukan penilaian jalur output, tetapi penilaian ISI DATA:
`DirectOutputState.depthVerdict() == 2` artinya dari ≥ 400.000 sampel yang tidak nol,
kurang dari 200 sampel yang byte rendahnya (low 8 bit) tidak nol.
Penghitungan ada di `DirectAudioOutput.countDepth()`, direset di `DirectAudioOutput` saat output dibuka
(`DirectOutputState.resetDepthCheck()`, baris ±323).
Dua kemungkinan, belum dibedakan:
1. File itu memang "24-bit palsu" (master 16-bit lalu dibungkus 24-bit). Maka label benar soal isi data, tetapi kata-katanya menyiratkan output gagal, padahal jalurnya bit-perfect.
2. Penghitungan keliru: hanya dilakukan pada awal lagu (intro sunyi/pelan = banyak sampel dekat nol), berhenti begitu verdict ≠ 0, dan tidak di-reset saat ganti lagu tanpa membuka ulang output.
   Perlu dicek: apakah `resetDepthCheck()` terpanggil setiap lagu baru/seek, bukan hanya saat output dibuka.

**Aturan yang disepakati dengan pengguna untuk 1.66:**
Label HIJAU `BIT-PERFECT` hanya jika SEMUA benar:
- flags output terbaca `DIRECT`,
- output BUKAN thread mixer (bukan MIXER / DEEP_BUFFER / AudioOut_D 48 kHz),
- perangkat output = jack (`WIRED_HEADPHONE 0x8`).
Dengan begitu label sama dengan hasil dump.

**Usulan implementasi:**
- Jalur (route) dan isi data dipisah menjadi dua pernyataan. Baris 1 = status jalur (hijau jika aturan di atas terpenuhi). Baris 2 = catatan isi data, bersifat informasi (abu-abu/netral, bukan oranye): mis. `isi data efektif 16-bit` hanya sebagai keterangan.
- Sumber status jalur: `DirectOutputState.route` (hasil `NativeDirectStream.routeInfo(handle)`, format `AudioOut_xx rate flags session`) — pastikan di dalamnya ada flags DIRECT dan thread bukan mixer; tambahkan pembacaan perangkat output (jack) bila belum ada.
- Perbaiki penghitungan kedalaman: reset per lagu/seek, jangan putuskan verdict dari intro sunyi (hitung sampai cukup sampel aktif DAN cukup durasi, atau teruskan menghitung sepanjang lagu).
- Verifikasi di HP: lagu yang tadi oranye diputar lagi + `adb shell dumpsys media.audio_flinger > af.txt` selama lagu main; label harus hijau bila dump DIRECT/jack.

## Perubahan 2 — Bug tema Classic: kontrol progres dan info posisi masih di atas

**Laporan pengguna:** pada pemutar tema Classic, tombol kontrol progres (seek bar + tombol transport)
dan info posisi (waktu 0:59 / 3:27) masih berada lebih ke atas, tidak sama dengan tema Adaptive.
(Ini lanjutan dari catatan Build 1.46: "kontrol dan progres di posisi yang sama dengan Adaptive".)

**Yang ditemukan di kode 1.65 (belum diukur di HP):**
- Dock bawah (`playerBottomDock` di `MainActivity.kt`, ±baris 618–626) berisi berurutan: metadata judul/artis/album → baris waktu (`elapsed`/`duration`) → `seek` → `transport` → `audioInfoInline`. Dock ditempel `Gravity.BOTTOM` pada `foreground`.
- Panel pemutar dipaksa setinggi viewport (`playerPanel.minimumHeight`, ±baris 327–334, Build 1.55) agar dock di dasar. Di Classic ada tambahan inset navbar transparan (`bottomInset`, ±baris 383–386; `FLAG_LAYOUT_HIDE_NAVIGATION` di ±baris 775–777), jadi tinggi viewport / padding bawah Classic berbeda dari Adaptive, dan itu kandidat penyebab dock lebih naik.
- Perbedaan lain: di Classic `audioInfoInline` bisa 3 baris (label panjang), sehingga dock bertambah tinggi dan seluruh kontrol terdorong ke atas. Label baru di Perubahan 1 (dua baris singkat) ikut membantu.
- `sizeClassicArt()` (±baris 648) menghitung ruang kotak sampul dari `playerBottomDock.height` — jangan sampai perbaikan posisi dock membuat kotak sampul tumpang tindih.

**Usulan:** samakan padding bawah dock dan tinggi viewport antara Adaptive dan Classic (hitung inset navbar sekali, dipakai sama), pastikan `audioInfoInline` maksimal 2 baris, lalu bandingkan posisi `seek` dan `transport` di kedua tema pada layar G6 (screenshot berdampingan).

## Urutan kerja Build 1.66
1. Perbaiki penghitungan kedalaman + pemisahan label jalur/isi data (Perubahan 1).
2. Rapikan posisi dock Classic (Perubahan 2).
3. Build di GitHub Actions, beri nama artifact `G6HiFiPlayer-Build166-v1.66.0`.
4. Uji di HP: (a) lagu 96 kHz yang tadi oranye, (b) tema Classic vs Adaptive posisi kontrol, (c) satu dump adb sebagai pembanding label.

## Jangan diubah
Jalur DIRECT native 24-bit packed (hasil Build 1.64/1.65) sudah terbukti bit-perfect di 4 dump; jangan sentuh engine, hanya label dan tata letak.
