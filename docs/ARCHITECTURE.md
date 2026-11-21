# Architecture

How Video Live Wallpaper 2.0 is put together. For what changed since 1.3 and
why, see [CHANGES-2.0.md](CHANGES-2.0.md).

## Overview

```
MainActivity                                  VideoWallpaperService.Engine (one per surface)
  controls ───────► SettingsStore ◄─────────── listens for changes
  ImportViewModel ─► MediaLibrary              visibility, Battery Saver, page offsets
  TextureView ─┐     (copies the file)         wallpaper colours (FrameGrabber)
               ▼                                        │
        PlaybackController ◄────────────────────────────┘
          main thread    ExoPlayer ──decodes video into──► SurfaceTexture
          render thread  VideoFrameRenderer (EGL + GLES2) ◄─┘
                         GifDecoder + GifPlayer ──GIF frames──► VideoFrameRenderer
                         CropGeometry (Fill / Fit / focus)
```

The app and the wallpaper run in the same process. Every control in the app
writes straight to `SettingsStore`; each wallpaper engine listens for
preference changes and applies them, so a running wallpaper updates
immediately. The in-app preview uses the same `PlaybackController` and
renderer as the wallpaper, in a frame shaped like the screen, so the preview
matches the wallpaper exactly.

## Files

| File | Role |
|---|---|
| `MainActivity.kt` | Settings screen: preview, file picking, controls, problem banner, launches the system wallpaper picker. |
| `PreviewFrame.kt` | Container with the screen's aspect ratio and rounded clipping for the preview `TextureView`. |
| `ImportViewModel.kt` | Runs imports outside the activity (survives rotation); one-time upgrade of a 1.x `content://` selection. |
| `MediaLibrary.kt` | Copies a picked file into `files/media/`, validates it (GIF parse / video track present), cleans up old copies, releases picker permissions. |
| `VideoWallpaperService.kt` | The live wallpaper. Each `Engine` translates wallpaper events and settings into `PlaybackController` calls, and reports the wallpaper's colours. |
| `PlaybackController.kt` | Plays one video or GIF onto one surface. Owns the ExoPlayer, the render thread, the renderer and the GIF player. |
| `VideoFrameRenderer.kt` | EGL context + OpenGL ES 2.0 drawing of the video texture or a GIF texture. |
| `CropGeometry.kt` | Pure Fill/Fit/focus math (unit tested). |
| `GifDecoder.kt` / `GifPlayer.kt` / `GifFiles.kt` | GIF decoding (pure Kotlin), frame timing, file sniffing/reading. |
| `FrameGrabber.kt` | Grabs one small frame for the wallpaper colours. |
| `PlaybackErrors.kt` | Sorts player errors into retry / file missing / unsupported (unit tested). |
| `WallpaperSettings.kt` | `SettingsStore` (typed preferences), `WallpaperSettings`, `WallpaperMedia`, `PlaybackProblem`. |
| `Formatting.kt` | Small pure helpers for the UI (unit tested). |

## Threads

`PlaybackController` has exactly two threads touching playback state:

- **Main thread** — all public methods, the `ExoPlayer` instance, listener
  callbacks. Nothing else ever calls the player. (In 1.x the render thread
  called `MediaPlayer.getVideoWidth()`/`isPlaying()` while the main thread
  could be releasing the player, which throws and crashes the wallpaper.) The
  video size reaches the render thread as plain numbers, posted from
  `onVideoSizeChanged`.
- **Render thread** (`wallpaper-render`, one per controller) — the EGL
  context, `VideoFrameRenderer`, `GifDecoder` and `GifPlayer`. The main thread
  sends it changes only by posting. Video frame notifications from the
  `SurfaceTexture` are delivered straight to this thread
  (`setOnFrameAvailableListener(listener, handler)`).

Blocking work that isn't playback (file copy, GIF validation, video probing,
frame grabbing for colours, sniffing a 1.x selection's type) runs on
background threads: a coroutine on `Dispatchers.IO` in the app, a single
executor in the service.

## Surface lifecycle

```
attachSurface(surface, w, h)            detachSurface()
  generation++                            generation++
  post ─► render: create renderer         player.clearVideoSurface()   (blocks until the decoder lets go)
          draw once (black/last frame)    post ─► render: release renderer
          post ─► main: if generation       wait for it (≤ 1 s)
                  unchanged, give the     return
                  player the renderer's
                  input surface
```

Every attach/detach bumps a generation number, and results from an older
generation are dropped. So a surface that is created and destroyed in quick
succession (common during unlock animations) can't leave a renderer or EGL
context behind, and the player is never handed a surface that is already
gone. `detachSurface` only returns after the EGL surface is destroyed, which
is what `Engine.onSurfaceDestroyed` requires.

If presenting a frame fails with `EGL_CONTEXT_LOST`, the controller rebuilds
the renderer on the same surface (at most three times).

## Drawing

- Video: a new frame arrives → render thread latches it (`updateTexImage`,
  always, so the decoder never runs out of buffers) → draws it if visible.
  There is no timer; the wallpaper draws exactly as often as the video
  produces frames.
- GIF: `GifPlayer` schedules one frame at a time on the render thread; each
  frame is composited by `GifDecoder`, uploaded to a 2D texture and drawn.
  GIF playback only runs while the wallpaper is visible and has a surface.
- Geometry (`CropGeometry.compute`) is recomputed only when the content size,
  surface size, mode or focus changes.

## Settings and visibility

`SettingsStore` keeps using the preference file and keys of version 1.x
(`selected_video_uri`, `playback_speed`, `scaling_mode` with the old
`MediaPlayer` constants), so upgrades keep their settings. New keys:
`media_kind`, `media_display_name`, `media_duration_ms`, `crop_focus_x/y`,
`trim_start_ms`, `trim_end_ms`, `parallax`, `pause_on_power_save`,
`restart_on_show`, `last_problem`.

An engine plays when the system says it's visible **and** not (Battery Saver
on **and** "pause in Battery Saver" enabled). With "start from the
beginning", every transition to playing seeks to the start of the loop.
With parallax enabled in Fill mode, the launcher's horizontal page offset
replaces the saved horizontal focus.

## Errors

Nothing ever clears the user's selection automatically. Instead:

| Situation | Handling |
|---|---|
| Decoder busy, codec init failed, other transient errors | Retried after 1 s, 2 s, 4 s; after that reported as `PLAYBACK_FAILED`, and retried again next time the wallpaper becomes visible. A file that played for 30 s since the last error gets fresh retries. |
| File missing, unreadable, permission lost | `SOURCE_MISSING` |
| Unsupported container/codec, corrupt GIF | `UNSUPPORTED` |
| OpenGL setup failed | `RENDERER_FAILED` |

The wallpaper engine stores the last problem in `last_problem` (cleared once
playback works again); the app shows it as a banner, together with problems
the preview itself hits.

## Storage

Picked files are copied to `files/media/wallpaper-<timestamp>.<ext>` via a
temporary `import-*.tmp` file that is renamed only after validation, so a
failed or interrupted import never touches the current wallpaper. Older
copies and leftovers are deleted after a successful import and on app start.
The picker's persisted permission is released after copying, so the app
holds no URI permissions at all.

## Build types

| Type | ID suffix | Shrunk (R8) | Signed with |
|---|---|---|---|
| debug | `.debug` | no | debug key |
| staging | `.staging` | yes | debug key |
| release | — | yes | (unsigned; sign for distribution) |

The suffixes let the debug and staging builds be installed side by side with a release build of the app.
