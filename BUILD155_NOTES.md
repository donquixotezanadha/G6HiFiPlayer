# Build 1.55 — Classic layout, transparent navbar, notification buttons
Branch: claude-build-155-player (dari claude-build-146-layout). Versi 1.55.0 (versionCode 155).

- Now Playing setinggi area yang terlihat (viewport ScrollView), bukan "tinggi layar - 150dp": dock kontrol selalu di bawah di Adaptive dan Classic.
- Classic: kotak cover = persegi terbesar di antara status bar dan dock, di tengah ruang itu (sizeClassicArt); margin samping 16dp.
- Classic: navigationBarColor transparan + SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION; nav bawah aplikasi diangkat setinggi inset bar sistem.
- Notifikasi: setiap tombol punya intent action + data URI sendiri (extras tidak dihitung saat PendingIntent dibandingkan); receiver mencatat log "G6Notification". Penyebab pasti next/previous tidak responsif BELUM terkonfirmasi di perangkat.
- Belum diuji di HP: posisi dock Classic, ukuran cover, nav bar transparan, tombol notifikasi.
