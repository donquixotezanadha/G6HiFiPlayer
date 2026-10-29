# Build 2.13 — catatan perubahan

Branch: `claude-build-213-player` (dari Build 2.12). Versi 2.13.0, versionCode 213, artifact `G6HiFiPlayer-Build213-v2.13.0`.
Suara/jalur DIRECT tidak diubah. Hanya tampilan Now Playing. Posisi dan area sentuh semua tombol tidak diubah (52dp / 60dp).

## Perubahan
Di 2.12 hanya ikon play yang mengecil; prev/next masih penuh. Sekarang ketiganya mengecil bersama (lewat padding, titik tengah tidak bergeser):

| Tombol | 2.11 | 2.12 | 2.13 |
|---|---|---|---|
| Play/pause (area 60dp) | 60dp | 42dp | **38dp** (padding 11dp) |
| Previous / Next (area 52dp) | 52dp | 52dp | **32dp** (padding 10dp) |

Rasio prev/next terhadap play dibuat mendekati 2.11 (32/38 = 0,84; sebelumnya 52/60 = 0,87), jadi ketiga tombol terlihat serasi.
Efek glow tidak diubah dari 2.12.

## Uji di HP
(a) Ikon play lebih kecil dari 2.12.
(b) Ikon previous dan next ikut mengecil dan terlihat serasi dengan play.
(c) Posisi tombol sama seperti 2.12; ketuk/sentuh tetap mudah.
