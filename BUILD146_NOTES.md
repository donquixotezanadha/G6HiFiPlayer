# Build 1.46 — WORK NOTES (belum ada perubahan kode; berhenti setelah analisis)
Branch: claude-build-146-layout (dari claude-build-145-classic-theme, commit 4f36aa7). Belum ada workflow build-146-claude.yml.

## Permintaan user (26 Okt 2026, dari screenshot Classic theme Build 1.45)
1. Posisi kontrol player + progres track harus SAMA seperti tema Adaptive (di bagian bawah layar).
2. Album art diperbesar; sesuaikan gap/margin.
3. Navbar (bar bawah Player/Albums/Setting + nav bar Android) transparan.
4. Periksa kontrol sentuh di notifikasi: tombol next/previous tidak responsif sejak perbaikan posisi tombol (Build 1.43: ikon prev/next ditukar kembali).

## Temuan dari screenshot (Classic, Build 1.45)
- Judul, artis, album, progress bar, tombol prev/next dan info audio tampil di ATAS layar (menimpa status bar), bukan di bawah.
- Kotak cover (artBox) berukuran sedang menimpa area itu: menutupi sebagian teks, progress bar, tombol prev/next.
- Bagian bawah layar kosong (hanya backdrop blur gelap).

## Kode terkait (MainActivity.kt)
- makePlayerPanel() ~baris 497-650: artBox (boxTop = statusbar+20dp, boxSide = min(lebar-72dp, tinggi-150dp-285dp-boxTop-12dp)), foreground FrameLayout berisi bottomDock (Gravity.BOTTOM) = metadata + times + seek + transport + audioInfoInline.
- playerPanel dibuat ~baris 349: SwipeFrameLayout, minimumHeight = heightPixels - dp(150). Dugaan: tinggi panel tidak penuh sehingga dock (Gravity.BOTTOM) menempel di atas. BELUM DIKONFIRMASI.
- applyPlayerPalette() ~baris 770: Classic -> dock.background = null, nav bar pakai playerSurface (CLASSIC_SURFACE hitam). applySystemBars() ~baris 763 set navigationBarColor = playerSurface (perlu TRANSPARENT).
- Nav bawah: LinearLayout `nav` ~baris 329, tinggi dp(64), ditambahkan di root setelah contentStage (~baris 392). Agar transparan perlu cek navState()/refreshNav() dan background nav.

## Rencana langkah (kecil, commit + build per tahap)
1. Perbaiki tinggi playerPanel (mis. dock selalu di bawah seperti Adaptive; ukur ulang pakai tinggi viewport nyata, bukan heightPixels-150dp).
2. Hitung ulang boxSide/boxTop dari ruang di antara status bar dan dock (ukur dock sebenarnya) -> album art lebih besar, gap atas/bawah rata.
3. navigationBarColor = TRANSPARENT + nav bar aplikasi transparan (kedua tema atau Classic saja — tanya user bila ragu).
4. Notifikasi: baca PlaybackKeepAliveService.kt (391 baris), NotificationActionReceiver.kt dan MainActivity.handleNotificationAction (~baris 2746): cek mapping aksi "previous"/"next" pada PendingIntent (request code/ requestCode unik, FLAG_UPDATE_CURRENT, aksi tertukar sejak 1.43?) dan guard 150 ms.
5. Tambah workflow build-146-claude.yml (salin dari build-145-claude.yml, artifact G6HiFiPlayer-Build146-v1.46.0), push, tunggu build, beri user APK.
6. Update HANDOFF_PLAYER.md dengan hasilnya.

## Belum diperiksa
- Apakah masalah posisi juga terjadi di tema Adaptive (user bilang Adaptive benar).
- Penyebab pasti tombol notifikasi tidak responsif.
