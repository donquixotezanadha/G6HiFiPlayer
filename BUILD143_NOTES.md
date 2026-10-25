# Build 1.43 (based on 1.42)
- Text/button colour is chosen like the Android media notification: near-black only when the surface luminance is above 0.5, otherwise white. Mid-tone covers now get white, as in the notification.
- The Previous and Next vector icons were swapped (Previous drew |◀ as ▶|). Fixed in ic_previous_24 / ic_next_24; this fixes the notification and the 4x2 widget. Button actions were already correct.
