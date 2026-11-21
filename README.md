# Video Live Wallpaper

A small Android app that lets you pick any video or animated GIF from your device and set it as a looping live wallpaper.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="260" alt="Main screen: choose a video, playback speed, crop mode">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="260" alt="Android's live wallpaper preview screen">
</p>

> **This is a fork** of [codeg0blin/video-live-wallpaper](https://github.com/codeg0blin/video-live-wallpaper).
> Version 2.0 reworks playback, adds several features and fixes stability problems;
> see [docs/CHANGES-2.0.md](docs/CHANGES-2.0.md) for what changed and why, and
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the app is put together.
> The screenshots above are still from version 1.3.

## Features

- Pick a video or animated GIF with the system file picker. The file is copied into the app's private storage, so the wallpaper keeps working if the original is moved, renamed, deleted or lives on a cloud drive or SD card.
- A preview shaped like your screen, drawn by the same renderer as the wallpaper, so what you see is exactly what you get
- Adjustable playback speed (0.25×–2.0×)
- Fill (crop to cover the screen) or Fit (show the whole frame, letterboxed if needed)
- In Fill mode, drag the preview to choose which part of the picture is visible; double-tap to centre it again
- Loop only part of a video (trim start and end)
- Optional: move the picture as you swipe between home screen pages (parallax, Fill mode, launchers that report page offsets)
- Optional: pause in Battery Saver
- Optional: start from the beginning every time the wallpaper appears
- Gives Android the wallpaper's colours, so Material You theming and the lock-screen clock match it
- Seamless looping (Media3/ExoPlayer) and portrait phone videos shown the right way up
- If the wallpaper can't play its file, the app says why instead of the wallpaper going silently black
- "Settings" in the system wallpaper picker opens the app
- English and Czech
- No ads, no analytics, no network access of any kind, no storage permissions

## Install

- **F-Droid / GitHub (upstream app, version 1.x):** see the [upstream project](https://github.com/codeg0blin/video-live-wallpaper).
- **This fork:** build it yourself (below), or download the APK artifact from a run of the *Build* workflow in this repository's Actions tab (unsigned release APK, or a debug APK that installs next to other builds).

Requires Android 8.0 (API 26) or newer.

## How it works

The app implements an Android `WallpaperService`. Each wallpaper engine owns a `PlaybackController`, which plays the file on its own render thread:

- **Videos** are decoded by Media3 ExoPlayer into a `SurfaceTexture`. `VideoFrameRenderer` draws that texture onto the wallpaper surface with OpenGL ES 2.0, applying the video's rotation and the Fill/Fit placement from `CropGeometry`. (Android's own video scaling relies on resizing the display surface, which live wallpapers aren't allowed to do, see upstream issue #1.) Trimming uses ExoPlayer's clipping, and the audio track is never decoded.
- **GIFs** are decoded by `GifDecoder.kt`, a small pure-Kotlin GIF89a decoder written for this app. `GifPlayer.kt` shows one frame at a time, timed by each frame's own delay divided by your speed setting, and frames are uploaded as textures to the same renderer, so every option works the same for GIFs and videos. GIFs are recognised by their file header, not their name. Transparent areas are drawn black, and GIF files over 50 MB are not supported.

The in-app preview uses the same `PlaybackController` with a `TextureView`, in a frame with the screen's aspect ratio.

Setting the wallpaper goes through Android's own system picker (`ACTION_CHANGE_LIVE_WALLPAPER`). The OS requires this for all live wallpaper apps, not just this one.

Android applies a live wallpaper to the home screen and lock screen together unless the system picker offers a choice (Pixels do: home, lock or both).

More detail, including the threading model and the surface lifecycle, is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy

The app has no internet permission and no storage permission, and contains no analytics, tracking or advertising code. It stores a private copy of the video or GIF you pick (in its own app storage, deleted when you pick another file or uninstall the app) and your settings, locally in `SharedPreferences`. Nothing is included in Android backups or device-to-device transfers.

## Building

Open the project in Android Studio, or build from the command line:

```
./gradlew assembleDebug      # debug build, ID ...videowallpaper.debug, installs next to other builds
./gradlew assembleStaging    # R8-shrunk release build signed with the debug key, ID ...videowallpaper.staging
./gradlew assembleRelease    # unsigned release build
./gradlew testDebugUnitTest  # unit tests (GIF decoder, crop geometry, error handling, formatting)
./gradlew lintDebug          # Android lint (errors fail the build)
```

Requires JDK 17 or newer (Android Studio's bundled JDK 21 works) and Android SDK platform 37. Toolchain: Android Gradle Plugin 9.4 (with its built-in Kotlin support), Gradle 9.8; `compileSdk` 37, `targetSdk` 36, `minSdk` 26.

The *Build* GitHub Actions workflow (`.github/workflows/build.yml`) runs lint, the unit tests and both builds on every push.

## Changelog

Per-version release notes are in [`fastlane/metadata/android/en-US/changelogs`](fastlane/metadata/android/en-US/changelogs) (and `cs-CZ`), named by version code. Version 2.0.0 is version code 11.

## Contributing

Bug reports and suggestions for the original app are welcome via [upstream issues](https://github.com/codeg0blin/video-live-wallpaper/issues).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

See [ATTRIBUTION.md](ATTRIBUTION.md) for a full list of the open-source libraries this app depends on, and a note on how the source code itself was written.
