# Version 2.0: what changed and why

Version 2.0.0 (version code 11) started from a review of version 1.3.0. This
document lists every finding of that review, what was done about it, and how
it was checked. The architecture that resulted is described in
[ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Bugs and robustness

| # | Finding in 1.3 | Change in 2.0 |
|---|---|---|
| 1.1 | **Render-thread crash risk.** The draw runnable on the render thread called `MediaPlayer.isPlaying()` / `getVideoWidth()`, while the main thread could be releasing that player; on a released player these throw `IllegalStateException`, uncaught on a `HandlerThread`, which kills the wallpaper process. | The player is only ever touched on the main thread. The video size is passed to the render thread as plain numbers from `onVideoSizeChanged` (`PlaybackController`). |
| 1.2 | **EGL context leak on fast surface create/destroy.** Renderer creation was posted to the render thread; if `onSurfaceDestroyed` ran first, `releaseRenderer()` saw no renderer, and the posted job then created one anyway (on a surface that was gone) and called `preparePlayer` with a stale holder. `renderer` was also shared between threads without `@Volatile`. | Renderer state lives only on the render thread. Attach/detach use a generation number, so stale results are dropped, and `detachSurface()` waits until the EGL surface is really destroyed before `onSurfaceDestroyed` returns. |
| 1.3 | **Selection cleared silently.** On most player errors (anything other than `MEDIA_ERROR_UNKNOWN`/`-38`, including the transient `MEDIA_ERROR_SERVER_DIED`) the service deleted the saved selection; the user just saw a black wallpaper. | Nothing is cleared automatically. Transient errors are retried with back-off (1 s, 2 s, 4 s, and again when the wallpaper next becomes visible); permanent ones (`SOURCE_MISSING`, `UNSUPPORTED`, `PLAYBACK_FAILED`, `RENDERER_FAILED`) are stored and shown as a banner in the app (`PlaybackErrors`, `PlaybackProblem`). |
| 1.4 | **File I/O on the main thread.** `GifFiles.looksLikeGif()` opened the content URI on the main thread in both the activity and the service; with cloud-backed files that risks an ANR. | The file type is detected once, during import on a background thread, and stored (`media_kind`). |
| 1.5 | **Persisted URI permissions piled up.** Picking a new file never released the permission for the old one. | Files are now copied (see 2.1); the picker permission is released after copying. The app holds none. |

## 2. Features

| # | Suggestion | Change in 2.0 |
|---|---|---|
| 2.1 | Copy the picked file into app storage. | `MediaLibrary` copies the file into `files/media/` with a progress indicator, checks free space, validates it (GIF parses / file has a video track), and only then switches the wallpaper over. Moving, renaming or deleting the original, removing the SD card, revoked permissions and cloud drives no longer affect the wallpaper. |
| 2.2 | Material You colours. | `Engine.onComputeColors()` returns `WallpaperColors` computed from one frame (at the trim start) by `FrameGrabber`; the system is notified whenever the file or trim start changes. |
| 2.3 | "Settings" button in the system wallpaper picker. | `android:settingsActivity` in `res/xml/video_wallpaper.xml`. |
| 2.4 | Seamless looping, consistent behaviour across devices. | Video playback moved from `MediaPlayer` to Media3 ExoPlayer (`REPEAT_MODE_ONE`, audio track disabled so it isn't even decoded). This also removed the workarounds for OEM `MediaPlayer` state-machine quirks (error -38, `weBelieveStarted`). |
| 2.5 | Choose the visible part in Fill mode. | Drag the preview to move the crop; double-tap to centre. Stored as `crop_focus_x/y` and applied by `CropGeometry`. |
| 2.6 | Trim the video. | "Loop section" range slider (videos only), implemented with ExoPlayer clipping. |
| 2.7 | Parallax with home screen pages. | Optional; in Fill mode the launcher's horizontal page offset (`onOffsetsChanged`) drives the horizontal crop. Works with launchers that report offsets (Pixel Launcher does). |
| 2.8 | Pause in Battery Saver. | Optional; the engine listens for `ACTION_POWER_SAVE_MODE_CHANGED`. Off by default, so behaviour doesn't change for existing users. |
| 2.9 | Start from the beginning when shown. | Optional; every time the wallpaper becomes visible it seeks to the start of the loop (GIFs rewind). |

## 3. Build and maintenance

| # | Finding | Change |
|---|---|---|
| 3.1 | Old toolchain: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, `targetSdk` 34. | AGP 9.4.1 (built-in Kotlin), Gradle 9.8.1, `compileSdk` 37, `targetSdk` 36 (current Google Play requirement), current AndroidX/Material libraries. Edge-to-edge (enforced from target 35) is handled with window insets. `targetSdk` 37 was not adopted yet: its behaviour changes would need their own review. |
| 3.2 | `proguard-rules.pro` referenced but missing; R8 disabled. | R8 and resource shrinking enabled, rules file added. Release APK: **2.0 MB** (1.3.0: 5.0 MB), even with Media3 added. |
| 3.3 | Unneeded `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` permissions. | Removed. The app requests no permissions at all. |
| 3.4 | `androidx.preference` used only for `getDefaultSharedPreferences`. | Removed (and `cardview`, `constraintlayout`). `SettingsStore` opens the same preference file directly. |
| 3.5 | No CI, tests only for the GIF decoder. | GitHub Actions workflow (lint, unit tests, debug + release builds, APK artifacts). New unit tests for crop geometry, error classification, formatting and settings compatibility (35 tests in total). Lint errors fail the build. The workflow runs green on this fork. |

## 4. Smaller items

| # | Finding | Change |
|---|---|---|
| 4.1 | Stale comments ("Chunk 1: EGL plumbing only. No GL drawing yet", re-prepare on speed change, `stopDrawLoop`). | Renderer, service and activity rewritten; comments describe the current code. |
| 4.2 | Hard-coded toast text; English only. | All text in resources; Czech translation (`values-cs`) and Czech store listing (`fastlane/metadata/android/cs-CZ`). |
| 4.3 | `eglSwapBuffers` result ignored. | Checked; on `EGL_CONTEXT_LOST` the renderer is rebuilt (up to three times). |
| 4.4 | Preview (`VideoView`/`ImageView`) rendered differently from the wallpaper. | The preview uses the same `PlaybackController`/renderer in a `TextureView`, in a frame with the screen's aspect ratio. `GifPreview.kt` was removed. |

Also changed along the way: the theme is now dark-only to match the app's
palette (with a DayNight parent, sliders and switches got light-mode colours
in light mode); a new launcher icon (phone screen with a play button, 2.0.1) with a themed (monochrome) variant; backups and
device transfers explicitly exclude the app's data (as `allowBackup="false"`
already did on older Android); debug and staging builds get their own
application IDs so they install next to a release build.

## 5. Own application ID

The fork's application ID is `cz.pribil.videowallpaper` (debug:
`cz.pribil.videowallpaper.debug`, staging: `cz.pribil.videowallpaper.staging`).
The original app is `io.github.codeg0blin.videowallpaper`, published on
F-Droid with the original author's signing key; a fork build with the same ID
could neither be installed next to it nor update it. With its own ID, the fork
is a separate app that installs alongside the original.

Consequences:
- Nothing carries over from an installed original app; pick the video again.
  (A first version of 2.0 copied an old `content://` selection automatically
  on first start. That code was removed together with the ID change, as there
  is nothing to upgrade from.)
- The Kotlin package and Gradle `namespace` keep the original name
  `io.github.codeg0blin.videowallpaper`, so the source stays comparable with
  upstream. Only the installed ID changed.

## Device test

Tested on a **Pixel 11 Pro XL, Android 17 (API 37)** (before the ID change;
afterwards the staging build with the new ID was installed next to the
original app and smoke-tested: pick, preview, set wallpaper, colours), with generated test
files (1080p H.264 with a running timecode, a portrait clip with a 90°
rotation tag, a VP9 WebM, an animated GIF, and a 12-byte text file named
`.mp4`), debug build and R8-shrunk staging build:

| Check | Result |
|---|---|
| Pick a video: copied with progress, preview plays, selection saved | ✅ |
| Fill crop in preview matches the wallpaper; dragging moves the crop and is saved | ✅ |
| Fit letterboxes; switching applies live to the running wallpaper | ✅ |
| Set wallpaper through the system picker; "Settings" button present there | ✅ |
| Portrait video with rotation tag shown upright | ✅ |
| WebM (VP9) imports and plays | ✅ |
| GIF imports, plays in preview and wallpaper; trim hidden for GIFs | ✅ |
| Non-video file rejected; previous wallpaper kept | ✅ |
| Trim 4.1–8.2 s at 1.85×: eight wallpaper samples all within 4.1–8.2 s | ✅ |
| Screen off: wallpaper process ~1 % CPU (paused); resumes on screen on | ✅ |
| 15 rapid screen on/off cycles: no crash, same process | ✅ |
| Wallpaper file deleted: banner "file can no longer be read", selection kept | ✅ |
| Selection whose read permission is gone (simulated with a `content://` URI): banner shown, speed/crop settings kept, no endless retries | ✅ (found and fixed during testing: a lost permission arrived as a generic IO error and was being retried) |
| Parallax: different part of the video on different home pages | ✅ |
| Battery Saver with the option on: wallpaper CPU 48 % → 0 %, resumes afterwards | ✅ |
| "Start from the beginning": 9.4 s before screen off → 0.8 s right after unlock | ✅ |
| Wallpaper colours computed and passed to the system (logged primary colour) | ✅ |
| R8-shrunk build: video, GIF, wallpaper, colours all work | ✅ |

CPU while visible was about 50 % of one core for a 1080p30 test video played
at 1.85× (≈55 frames per second), split between the render thread, ExoPlayer
and the hardware decoder.

### Not verified

- Older Android versions and other vendors: only one device (Android 17) was
  available. `minSdk` stays 26.
- The `EGL_CONTEXT_LOST` recovery path (can't be triggered on demand).
- Battery Saver and the theme with the phone in light mode were checked only
  through the settings described above.
