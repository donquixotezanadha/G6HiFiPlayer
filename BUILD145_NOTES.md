# Build 1.45 (based on 1.44)
1. Setting has a new THEME option: ADAPTIVE (as in 1.44, follows the cover colour) or CLASSIC. The choice is saved and the screen rebuilds at once.
2. Classic: the cover of the playing track is shown behind every page (Now Playing, Albums, Setting, queue) as a softened, darkened ("semi black") background. Accent colour is the original purple; text is white and grey; cards are translucent black; progress bar, play button and selected tab use the purple.
3. Classic Now Playing shows the cover as a square box above the track details (the full-screen cover and the bottom tint of Adaptive are not used).
4. WAV tags: MediaMetadataRetriever returns no tags for most WAV files. New WavTags.kt reads the LIST/INFO chunk (title, artist, album, track) and the ID3v2 chunk ("id3 ", or an ID3 block in front of the RIFF header; v2.2/2.3/2.4, Latin-1/UTF-16/UTF-8) including the embedded cover (APIC). The embedded WAV cover is used before the folder picture. RF64 headers are handled.
5. Library cache version 145: the saved library is scanned once more on first start so WAV tags are picked up; the cover disk-cache key changed so WAV files cached earlier as "no cover" are looked up again.
Not yet checked on the phone: Classic look on very bright covers, readability of grey text over the backdrop, WAV files with unusual tag layouts.
