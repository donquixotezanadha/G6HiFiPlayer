# Build 2.14 — catatan perubahan

Branch: `claude-build-214-player` (dari Build 2.13). Versi 2.14.0, versionCode 214, artifact `G6HiFiPlayer-Build214-v2.14.0`.
Suara/jalur DIRECT tidak diubah.

## 1. Glow sentuh tombol: 30%
Puncak transparansi glow turun dari 50% (0x80) ke **30%** (0x4D). Tiga tahap gradien lainnya diskalakan dengan faktor yang sama (0x66 -> 0x3D, 0x30 -> 0x1D), jadi bentuk glow tetap sama, hanya lebih halus. Ukuran dan animasi tidak diubah.

## 2. Widget lama dihapus, dua widget baru
Dihapus semua: `G6MusicWidgetProvider` (+ `G6CompactWidgetProvider`), 3 layout, 2 file xml provider, 9 drawable `widget_*`.
Penyebab widget lama gagal: widget "2x2" tidak pernah didaftarkan di manifest, dan yang "4x2" sebenarnya tinggi 60dp (jadi 4x1).

Widget baru (satu file `G6Widgets.kt`), keduanya terdaftar di manifest:

| Widget | Nama di daftar widget | Isi |
|---|---|---|
| 4x2 | Music 4x2 | cover, judul, artis, progress bar, previous / play-pause / next. **Background hitam 80% (#CC000000)**, sudut membulat |
| 2x2 | Music 2x2 | cover persegi penuh, previous / play-pause / next di bagian bawah (bayangan gelap) |

- Ketuk cover/kartu = buka player. Tombol = kontrol putar.
- 4x2 memilih layout besar (cover 130dp) atau kompak (cover 90dp) sesuai tinggi widget di launcher.
- Hanya tampilan gelap (sama seperti player sejak 2.0). Tidak ada service/timer baru: widget digambar dari state yang didorong player.
- Cover disimpan sekali per lagu (400 px, persegi); play/pause hanya update parsial.
- Widget lama di layar utama akan hilang/rusak setelah update: **hapus lalu tambahkan lagi** "Music 4x2" dan "Music 2x2".

Workflow memeriksa isi APK: kedua receiver baru ada, provider lama tidak ada.

## Uji di HP
(a) Glow di tombol prev/play/next lebih tipis dari 2.13.
(b) Tambahkan widget "Music 4x2": latar hitam 80%, cover + judul + progress + 3 tombol.
(c) Tambahkan widget "Music 2x2": cover + 3 tombol di bawah.
(d) Play/pause dari widget, next/previous, dan ketuk widget membuka player.
