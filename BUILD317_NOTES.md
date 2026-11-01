# Build 3.17 - single launcher, no alias switching

Base: Build 3.16 (branch build-316-no-starting-window).

## Problem
After switching Dark/Light, the app was sometimes read as two apps and open/close was no longer seamless.

## Cause
Builds 3.10-3.12 and 3.14-3.16 used two launcher activity-aliases (MainActivityDark / MainActivityLight).
`syncLauncherThemeAlias()` disabled the alias the running task was started from and enabled the other one
while the app was open. The system and the launcher then saw two different launch entries for one app.
The aliases only existed to colour Android's starting window, which Build 3.16 already disables
(`windowDisablePreview=true`), so they had no remaining purpose.

## Change
- AndroidManifest.xml: both activity-alias blocks removed; MainActivity is the only LAUNCHER entry.
- MainActivity.kt: `syncLauncherThemeAlias()` and both calls removed. Theme still comes from the saved preference.
- Version 3.17 (versionCode 317).
- Workflow build-317-single-launcher-no-alias.yml checks that no alias or component toggling is left.

## Unchanged
Audio engine, native code, widgets, cache, layouts, in-app splash (112dp foreground logo, theme background).

## Test
1. Install over 3.16, close the app from recent apps, open it again.
2. Switch Dark -> Light -> Dark, press Home, open from the launcher icon, then from recent apps.
3. Expect one app only, with no second instance or second splash from the theme switch.
