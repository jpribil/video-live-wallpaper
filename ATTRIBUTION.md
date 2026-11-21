# Attribution & Open Source Notices

This document lists everything this app is built on, for transparency and
because it's a prerequisite for F-Droid submission. It also notes how the
app's own source code was produced.

## How the app's source code was written

This app's source code (all Kotlin files under `app/src`, the unit tests,
all layout/resource XML, and the Gradle build files) was written specifically for this project with the help of an
AI assistant (Claude, by Anthropic), based on a plain-language description
of the desired feature set. No code was copied from a tutorial, blog post,
or another app's repository. All of it is original to this project and is
released under the license in `LICENSE` (Apache License 2.0).

It uses standard, publicly documented Android SDK and AndroidX APIs in the
ordinary way any Android app would — see the dependency list below for
exactly which libraries are linked in.

### Version 2.0 (this fork)

The 2.0 rework (`PlaybackController.kt`, `CropGeometry.kt`,
`MediaLibrary.kt`, `ImportViewModel.kt`, `PreviewFrame.kt`,
`FrameGrabber.kt`, `PlaybackErrors.kt`, `WallpaperSettings.kt`,
`Formatting.kt`, the rewritten `MainActivity.kt`, `VideoWallpaperService.kt`
and `VideoFrameRenderer.kt`, the new tests, the Czech translation and the
documentation in `docs/`) was likewise written with the help of an AI
assistant (Claude, by Anthropic), starting from a review of version 1.3.0;
see `docs/CHANGES-2.0.md`. Nothing was copied from elsewhere. Video decoding
now uses the Media3 ExoPlayer library (listed below) through its public API.

### `VideoFrameRenderer.kt` specifically

This file implements a small EGL/OpenGL ES 2.0 pipeline (the video player
(MediaPlayer up to 1.3, Media3 ExoPlayer since 2.0) decodes into a `SurfaceTexture`, a shader draws it into the wallpaper's
`Surface` with Fill/Fit crop or letterbox geometry), added to fix scaling
modes that don't work on a `WallpaperService` surface — see issue #1 for
the background. Since version 1.3.0 the video shader also applies the
transform matrix that `SurfaceTexture` provides for each frame
(`SurfaceTexture.getTransformMatrix`), which carries the video's rotation
tag, so portrait phone recordings display upright; this is the documented
use of that API. The same file also draws GIF frames (see below) with a
second, plain `GL_TEXTURE_2D` shader. No code was copied into this file
from anywhere. Two things from outside this repo informed how it was
written, noted here for transparency since the document's purpose is to say
where ideas and code came from, not just libraries:

- **`Gles2WatchFaceService`**, part of the Android Wear platform APIs,
  confirmed that rendering via a manually-created EGL window surface
  bound to a `WallpaperService`/watch face Engine's `Surface` is an
  officially supported pattern on Android, before this file was written
  against that pattern from scratch.
- **[alynx-live-wallpaper](https://github.com/AlynxZhou/alynx-live-wallpaper)**
  (Apache License 2.0) and **[kinewall](https://github.com/eaangrino/kinewall-video-wallpaper-android)**,
  two other open-source Android video wallpaper apps, were consulted
  (via their public descriptions/READMEs, not their source) to confirm
  that a MediaPlayer → SurfaceTexture → OpenGL renderer architecture is
  a workable, precedented solution to this class of problem, before this
  file's own independent implementation was written.

### GIF support (`GifDecoder.kt`, `GifPlayer.kt`, `GifFiles.kt`; `GifPreview.kt` until 1.3)

Added in version 1.3.0 for issue #2. `GifDecoder.kt` is a small GIF
decoder written from the public GIF87a/GIF89a file format specification
(block structure, LZW decompression, interlacing, transparency and
disposal methods). It is plain Kotlin with no Android or third-party
imports, so it can be unit-tested on an ordinary JVM. No code was copied
into it from anywhere, and the app links no GIF library.

- Issue #2 mentioned **[redwarp/gif-wallpaper](https://github.com/redwarp/gif-wallpaper)**
  as an example of an existing open-source GIF wallpaper app. Its source
  code was not consulted or used for this work.
- During development the decoder's output was cross-checked against the
  frames that the Pillow imaging library (a development-time Python tool,
  not part of this app or repository) decoded from generated test GIFs.
  The repository's own unit tests build their GIF files in code, so no
  binary test files are checked in.

## Runtime dependencies (linked into the app)

All of the following are open source and Apache License 2.0, pulled
automatically by Gradle from Google's Maven repository (no source code
copied into this repo):

| Library | Version | License | Purpose |
|---|---|---|---|
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx/releases/core) | 1.19.1 | Apache 2.0 | Kotlin extensions, window insets, receiver registration |
| [androidx.appcompat:appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.8.0 | Apache 2.0 | Backward-compatible activity base class |
| [com.google.android.material:material](https://github.com/material-components/material-components-android) | 1.14.0 | Apache 2.0 | Buttons, sliders, switches, cards, progress indicator, theme |
| [androidx.activity:activity-ktx](https://developer.android.com/jetpack/androidx/releases/activity) | 1.13.0 | Apache 2.0 | File-picker result API, edge-to-edge |
| [androidx.lifecycle:lifecycle-runtime-ktx / lifecycle-viewmodel-ktx](https://developer.android.com/jetpack/androidx/releases/lifecycle) | 2.11.0 | Apache 2.0 | Background file import that survives rotation |
| [androidx.media3:media3-exoplayer](https://developer.android.com/media/media3) | 1.11.1 | Apache 2.0 | Video decoding and seamless looping (since 2.0; replaces `MediaPlayer`) |

Version 1.x also used `androidx.constraintlayout`, `androidx.cardview` and
`androidx.preference`; 2.0 no longer needs them.

## Test-only dependencies (not included in the app)

Used only when running `./gradlew test` on a developer machine. They are
not part of the APK, and release builds do not need them.

| Library | Version | License | Purpose |
|---|---|---|---|
| [junit:junit](https://junit.org/junit4/) | 4.13.2 | Eclipse Public License 1.0 | Unit tests (GIF decoder, crop geometry, error handling, formatting) |

## Build tooling

| Tool | Version | License |
|---|---|---|
| [Android Gradle Plugin](https://developer.android.com/build) (with built-in Kotlin support) | 9.4.1 | Apache 2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | bundled with AGP | Apache 2.0 |
| [Gradle](https://gradle.org/) | (wrapper-managed) | Apache 2.0 |

## Android platform APIs used (no extra license — part of the OS, not a dependency)

These are not bundled into the app; they're system services every Android
device already provides:

- `android.service.wallpaper.WallpaperService` — the live wallpaper engine
- `android.media.MediaMetadataRetriever` — checks that a picked file contains video, reads its length, and grabs a frame for the wallpaper colours
- `android.app.WallpaperColors` — tells the system the wallpaper's colours (Material You)
- `android.app.WallpaperManager` — system "set wallpaper" intent
- `android.content.ContentResolver` — reads the picked file once to copy it into private storage (the picker's permission is released afterwards)
- `android.opengl.EGL14` / `android.opengl.GLES20` / `android.opengl.GLES11Ext` / `android.opengl.GLUtils` — EGL context and OpenGL ES 2.0 drawing, used by `VideoFrameRenderer.kt` to render video and GIF frames with correct Fill/Fit scaling
- `android.graphics.SurfaceTexture` — receives decoded video frames as a GL texture, and supplies each frame's transform matrix
- `android.graphics.Bitmap` — holds decoded GIF frames for upload to the GL texture
- `android.view.TextureView` — the in-app preview surface
- `android.os.Handler` / `android.os.HandlerThread` — schedule GIF frames and keep decoding off the main thread

## What this app does NOT include

For transparency, since this matters for F-Droid's review and for anyone
auditing the repo:

- No analytics or telemetry SDKs (no Firebase, no Crashlytics, etc.)
- No advertising SDKs or ad network code
- No network permissions of any kind — the app never connects to the internet
- No proprietary/closed-source libraries
- No third-party GIF or image-loading libraries — GIF decoding is done by
  the app's own code. The only media library is Google's open-source Media3
  ExoPlayer, used for video (it makes no network connections here: the app
  has no internet permission and only plays local files)
- No permissions at all (no storage, no internet)
- No tracking of any kind; the only persisted data is a private copy of the
  video or GIF you pick, plus your settings, stored locally on your own
  device and excluded from backups

## License

This project is licensed under the **Apache License 2.0** — see `LICENSE`.
