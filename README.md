# LuckySaver

Personal Android app for saving Instagram photos, videos, reels, stories and highlights.

Native Kotlin + Jetpack Compose. No React Native, no backend — everything runs on the device.

## How it works

Share a post from Instagram (or paste a link), the app resolves the media URLs, and downloads
run in the background through WorkManager with a progress notification. Files land in a single
`Pictures/LuckySaver` album.

Instagram closed its logged-out endpoints, so you log in through a WebView on instagram.com and
the app reuses those cookies for API calls. Use a secondary account: automated requests can get
an account checkpointed.

## Build

```bash
./gradlew installDebug
```

Requires Android SDK 36 and JDK 17. `minSdk` is 29.

## Layout

| Package     | What lives there                                        |
|-------------|---------------------------------------------------------|
| `core`      | Link parsing, shortcode↔media id, media JSON parsing     |
| `resolve`   | Session cookies and the resolver strategies              |
| `download`  | MediaStore writing and the download worker               |
| `data`      | Room download history                                    |
| `ui`        | Compose screens                                          |

## Notes

Scraping Instagram is against its Terms of Service. This is a personal, sideloaded tool — not
something to publish on Play Store. Endpoints change often; when fetching breaks, the request
headers in `resolve/` are the first place to look.
