# Build 2.17 — catatan perubahan

Branch: `claude-build-217-player` (dari Build 2.16). Versi 2.17.0, versionCode 217, artifact `G6HiFiPlayer-Build217-v2.17.0`.
Suara/jalur DIRECT tidak diubah. Dua widget didesain ulang mengikuti gambar referensi (IMG-20261029-WA0002).

## Widget 4x2 ("Music 4x2")
- Cover persegi membulat di kiri (sudut sedikit lebih bulat), hampir setinggi widget.
- Kanan: judul besar, penyanyi, album (dua baris kecil terpisah), progress bar tipis, lalu previous / play / next.
- Ikon previous/play/next berupa garis tipis (outline) seperti gambar; play sedikit lebih besar.
- Latar tetap hitam 80% membulat (permintaan 2.14); teks putih.
- Dua ukuran (kompak dan besar) sesuai tinggi widget di launcher, tata letak sama.

## Widget 2x2 ("Music 2x2")
- Cover bulat besar, tanpa kartu di belakangnya.
- Satu tombol play/pause persegi membulat kecil di kiri bawah, menimpa tepi cover (seperti gambar). Previous/next dihapus dari 2x2 karena tidak ada di gambar.
- Ketuk cover = buka player.

## Badge Hi-Res di widget
Lagu 24-bit: badge digambar di widget setelah cover dipotong (di cover bulat posisinya digeser ke dalam supaya tidak terpotong lingkaran). Badge sekarang satu file bersama (`HiResBadge.kt`), dipakai juga oleh player (2.16).

## Uji di HP
Hapus widget lama lalu tambahkan lagi "Music 4x2" dan "Music 2x2".
(a) 4x2: tata letak seperti gambar, tombol berfungsi, progress jalan.
(b) 2x2: cover bulat + tombol play kecil kiri bawah.
(c) Lagu 24-bit: badge Hi-Res di kedua widget.
