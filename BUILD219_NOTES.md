# Build 2.19 — catatan perubahan

Branch: `claude-build-219-player` (dari Build 2.18). Versi 2.19.0, versionCode 219, artifact `G6HiFiPlayer-Build219-v2.19.0`.
Suara/jalur DIRECT tidak diubah.

## Widget 4x2 ("Music 4x2")
- Album art besar: persegi setinggi widget, menempel di kiri, atas dan bawah (tanpa jarak). Gambarnya gambar Hi-Res yang sama dengan di player (`hires_cover.png`).
- Karena art lebih besar, kolom kanan lebih sempit: progress track otomatis lebih pendek dan seluruh isi (judul, penyanyi, album, progress, tombol) lebih rapat tapi tetap memenuhi area.
- Seluruh background round rectangle (radius 22dp, hitam 80%); sudut kiri art dipotong mengikuti radius kartu, jadi widget tampak satu bentuk membulat.
- Ukuran art dihitung dari tinggi widget di launcher (per widget), jadi pas di semua ukuran.
Widget 2x2 dan bagian lain tidak diubah dari 2.18 (notifikasi dan lock screen tetap tanpa art).

## Uji di HP
Hapus widget 4x2 lama lalu tambahkan lagi: art Hi-Res besar penuh di kiri, kartu membulat utuh, tombol dan progress berfungsi.
