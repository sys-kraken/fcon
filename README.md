# FCon for Android

Converts YouTube / Facebook / Instagram links to mp3, flac, aac, mp4 or mkv **on the phone itself**
(bundled yt-dlp + ffmpeg via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)),
so the requests come from the phone's own IP instead of a server that YouTube blocks.

The screen is the FCon web page, bundled in the app. The in-app code (`Api.kt`, `JobManager.kt`) answers the
same JSON calls the FCon server did, so the page works unchanged.

## How it fits together
- `web/` is a copy of the website's `public/` folder. `node tools/sync-web.mjs` copies it to
  `app/src/main/assets/www`, swaps in `tools/app-config.js` as `config.js` (the shim that talks to the app)
  and patches the download buttons to save into `Downloads/FCon`.
- `MainActivity` answers every request to `https://appassets.androidplatform.net` itself: `/api/*` goes to `Api`,
  everything else is read from `assets/www`. No network port is opened.
- `ConversionService` is a foreground service so long conversions survive the screen turning off.
- yt-dlp updates itself on the nightly channel at start-up (at most every 12 h) and retries once with a fresh
  version when a download fails in a way that usually means YouTube changed something.

## Build
Needs JDK 17 and the Android SDK (`local.properties` -> `sdk.dir`).

```
node tools/sync-web.mjs
gradlew.bat assembleDebug      # debug APKs: app/build/outputs/apk/debug/
gradlew.bat assembleRelease    # signed release APKs (needs keystore.properties)
```

Two APKs are produced: `arm64-v8a` (almost every phone made since 2016) and `armeabi-v7a` (old 32-bit phones).

## Signing
`keystore.properties` (git-ignored) points at the release key. **Back the key up** (`../fcon-android-keys`):
if it is lost, friends can no longer update the app and must uninstall it first.

## Updating
In the app's menu, **Check for updates** reads the newest GitHub release of this repo and installs it over the old one
(same signing key, higher version code). Publish a release with `gradlew assembleRelease`, then
`gh release create vX.Y.Z dist/app-*-X.Y.Z.apk`. The APK name must contain the CPU type (`arm64-v8a` / `armeabi-v7a`);
the app picks the matching one. Build another version with `-PappVersion=X.Y.Z`.
