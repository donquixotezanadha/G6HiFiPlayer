# Build 2.31 — catatan perubahan

Branch: `claude-build-231-player`, berbasis langsung pada Build 2.30.

## Perubahan visual
- Logo Hi-Res diperkecil menjadi 60% ukuran sebelumnya: 12% dari lebar cover, sebelumnya 20%.
- Deteksi bit depth FLAC/WAV dilakukan saat pemindaian library melalui `AudioSniffer`, lalu disimpan di `library-cache.json`.
- Versi cache library dinaikkan agar library lama dipindai ulang sekali dan bit depth ikut tersimpan.
- Badge ditampilkan pada seluruh cover album yang memuat setidaknya satu track 24-bit: grid Albums, halaman detail album, strip album terkait, serta cover track pada daftar Songs.
- Badge Now Playing/mini-player menggunakan bit depth hasil scan sebagai fallback selama metadata playback sedang dibaca. Ini mencegah badge 24-bit berkedip hilang saat berganti antar-track 24-bit.
- Tidak ada perubahan pada jalur audio native/DIRECT, decoder, atau konfigurasi output.

## Verifikasi
GitHub Actions harus memastikan APK debug berhasil dibangun, library native `libg6direct.so` tersedia, dan kedua widget terdaftar. Perilaku visual akhir tetap perlu dikonfirmasi di LG G6.
