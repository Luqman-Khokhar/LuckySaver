# LuckySaver

Personal Android app for saving Instagram photos, videos, reels, stories and highlights.

Native Kotlin and Jetpack Compose. No React Native, no Expo, no backend — everything runs on
the device.

## What it does

- Share a post from Instagram, or paste a link, and it saves to your gallery
- A floating bubble over other apps: copy a link in Instagram, tap the bubble, done
- Posts, reels, carousels, stories, highlights and profile pictures
- Downloads run in the background with progress and completion notifications
- A story watchlist that saves chosen accounts' stories before they expire
- Download history with duplicate skipping

Instagram closed its logged-out endpoints, so a login is required even for public posts. The app
opens the real instagram.com page in a WebView and reuses the session cookies; it never sees your
password. **Use a secondary account** — automated requests can get an account checkpointed.

## Requirements

| Thing | Version |
|-------|---------|
| JDK | 17 |
| Android SDK | compileSdk 36, minSdk 29 |
| Gradle | 8.14.3 (wrapper included) |
| AGP / Kotlin | 8.12.0 / 2.1.20 |

`local.properties` must point at your SDK, for example `sdk.dir=/home/you/Android/sdk`.

## Commands

```bash
# debug build (unminified, debuggable, WebView remote debugging on)
./gradlew assembleDebug                 # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                  # build and install on the connected device

# release build (R8 minified, shrunk, signed, ~3 MB)
./gradlew assembleRelease               # APK at app/build/outputs/apk/release/app-release.apk
./gradlew installRelease                # build and install on the connected device

# tests and checks
./gradlew testDebugUnitTest             # unit tests (link parsing, shortcodes, media JSON)
./gradlew compileReleaseKotlin          # fast compile check, no packaging
./gradlew clean                         # wipe build outputs

# on the device
adb devices                                                  # confirm the phone is connected
adb shell am start -n com.luqman.luckysaver/.MainActivity    # launch
adb uninstall com.luqman.luckysaver                          # remove (wipes login and history)
adb logcat | grep -i luckysaver                              # watch logs
```

Debug and release are signed with different keys, so they cannot replace each other. Switching
between them means uninstalling first, which wipes the login and the download history — saved
files in the gallery are untouched.

## Signing

Release credentials live in `playstore/`, which is gitignored and must never be committed:

```
playstore/luckysaver.jks           # keystore
playstore/keystore.properties      # passwords, alias, certificate fingerprints
```

**Back this folder up off the machine.** Losing it means no further updates can install over an
existing LuckySaver; you would have to uninstall and lose history. Without the file, release
builds fall back to the debug key so a fresh clone still builds.

## Project layout

| Package | What lives there |
|---------|------------------|
| `core` | Link parsing, shortcode ↔ media id, media JSON parsing — pure Kotlin, unit tested |
| `resolve` | Session cookies, the resolver strategies, remote endpoint config |
| `download` | MediaStore writing, download worker, notifications, story watch worker |
| `data` | Room database (history, watchlist) and settings |
| `ui` | Compose screens: welcome, home, login, history, settings, watchlist |
| `overlay` | Floating bubble service and its custom view |

Files are saved to `Pictures/<album>`, where the album name, file name template, quality,
Wi-Fi-only downloads and duplicate skipping are all configurable in Settings.

## When it breaks

Instagram changes its private endpoints every few months. Rather than rebuilding, edit
[`config/endpoints.json`](config/endpoints.json) and push — the app fetches it at most twice a
day and falls back to the values compiled in if it is missing or malformed.

If fetching fails, check in this order: the session (the app says when it has expired), then the
paths and app id in that config file, then the request headers in `resolve/IgSession.kt`. A
desktop user agent is used on purpose: Instagram's mobile login page renders blank inside a
WebView.

## Notes

Scraping Instagram is against its Terms of Service. This is a personal, sideloaded tool, not
something to publish on Play Store.
