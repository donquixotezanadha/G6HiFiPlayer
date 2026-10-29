# Build 2.11 — catatan perubahan

Branch: `claude-build-211-player` (dari Build 2.10). Versi 2.11.0, versionCode 211, artifact `G6HiFiPlayer-Build211-v2.11.0`.
Suara/jalur DIRECT tidak diubah. Hanya tampilan Now Playing.

## Penyebab
1. Tombol prev/play/next di 2.9 dan 2.10 hanya membesar areanya: ikon vektor berukuran tetap 24dp dan `scaleType = CENTER_INSIDE`
   tidak pernah memperbesar gambar, hanya memperkecil. Jadi ikon tetap 24dp berapa pun ukuran areanya.
2. Dock kontrol di 2.10 lebih tinggi ~24dp dari 2.8 (area tombol 96dp + jarak tipis). `sizeDarkArt()` menghitung sisa ruang dari tinggi
   panel; kalau isi lebih tinggi dari halaman, panel ikut melebar sehingga cover tidak mengecil dan baris paling bawah tertutup bottom bar.

## Perubahan
1. Area tombol kembali ke ukuran 2.8: prev/next 52dp, play 60dp (`TRANSPORT_SIDE_DP`, `TRANSPORT_PLAY_DP`). Posisi sama seperti 2.8.
2. Ikon sekarang memenuhi area (`FIT_CENTER`, padding 0): glyph kira-kira 2x lebih besar dari 2.8 (prev/next ~26x30dp, play ~30x38dp).
3. Jarak deskripsi -> progress kembali ke 14dp (`PROGRESS_TOP_GAP_DP = DOCK_GAP_DP`), seperti 2.8.
4. `sizeDarkArt()` memakai tinggi halaman yang terlihat (`playerPanel.minimumHeight`), bukan tinggi panel yang bisa melebar,
   sehingga cover mengecil dulu dan dock tidak pernah turun ke bawah bottom bar.
5. Tombol "+" dan "x" abu-abu dari 2.10 tidak diubah.

## Uji di HP
(a) Baris deskripsi paling bawah (format + BIT-PERFECT) terlihat penuh di atas bottom bar, posisi sama seperti 2.8.
(b) Ikon prev/play/next jelas lebih besar, posisinya sama seperti 2.8.
(c) Efek glow saat disentuh masih halus.
