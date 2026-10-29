# Build 2.7 — catatan perubahan

Branch: `claude-build-207-player` (dari Build 2.6). Versi 2.7.0, versionCode 207, artifact `G6HiFiPlayer-Build207-v2.7.0`.
Suara/jalur DIRECT tidak diubah.

## Perubahan
1. Gap Now Playing sedikit lebih lega dan merata: album art - judul/artis - progress - kontrol - info.
   - `DOCK_GAP_DP` = 14dp (waktu, kontrol, dan info line), `DOCK_TOP_PAD_DP` = 14dp (di atas judul).
   - Seluruh blok Now Playing tetap satu kesatuan; bottom bar tidak ikut. Album art tetap otomatis di tengah
     antara status bar dan judul (`sizeDarkArt`), jadi gap atas-bawah cover tetap seimbang.
2. Progress track: bagian belum jalan abu-abu (`PROGRESS_TRACK_GREY`), bagian yang sudah jalan warna aksen, thumb putih.

## Uji di HP
(a) Jarak antar bagian Now Playing tidak mepet; cover tetap di tengah antara status bar dan judul.
(b) Progress: abu-abu, bagian jalan ungu aksen, thumb putih.
