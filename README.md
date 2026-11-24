# Album Lock

A small Android app that puts the album cover of the song you are playing on your lock screen, iOS style: a large sharp cover with rounded corners on top of a blurred, darkened copy of the same artwork. When the music stops, your normal lock-screen photos come back.

Built for and tested on a Samsung Galaxy S21 (One UI 7, Android 15), which has no built-in way to do this. It should work on any phone running Android 10 or newer.

## What it does

- **While music plays**, the cover of the current album becomes the lock-screen wallpaper. It works with any player that publishes a media session (Spotify, YouTube Music, etc.).
- **When nothing has played for 90 seconds**, your normal lock-screen photo is put back.
- **Several normal photos?** Pick them all. Like Samsung's own photo rotation, the app switches to the next one each time the screen turns off, so you see a different photo every time you wake the phone.
- Only the lock screen changes. Your home-screen wallpaper is never touched.

## How it works

- `ArtListener` is a `NotificationListenerService`. Notification access is the only way for a regular app to see other apps' media sessions. The app does not read or store your notifications.
- On every play, pause or track change it checks which session is playing and takes the cover from its metadata.
- `Renderer` draws the lock screen in memory: blurred background (box blur at 1/10 size, scaled up) plus the cover. The result is handed to `WallpaperManager` as a JPEG, for the lock screen only.
- Your normal photos are re-encoded once into the app's private storage (`files/defaults/`), because Android does not let apps read the current lock-screen wallpaper back.

### Storage and battery

- The app writes **no files per song**. Each cover is rendered in memory, and Android overwrites the same lock-wallpaper file every time, so nothing piles up.
- Covers are passed as JPEG (a few hundred KB per write instead of the several MB a PNG would take) and are excluded from device backup.
- Songs from the same album reuse the cover without writing anything.
- No timers and no polling: the app only reacts to media-session callbacks and the screen-off broadcast.
- No internet permission. Nothing leaves the phone.

## Build

There is no Gradle. `build.sh` calls the Android SDK command-line tools directly.

Requirements:
- Android SDK with `build-tools` and a `platforms/android-XX` package
- JDK 17 or newer
- `adb` to install on a phone

```bash
./build.sh            # builds build/AlbumLock.apk
./build.sh install    # builds and installs it on the phone connected over adb
```

Environment variables (with defaults):

| Variable | Default |
|---|---|
| `ANDROID_HOME` | `~/Android/Sdk` |
| `BUILD_TOOLS` | `36.0.0` |
| `PLATFORM` | `android-35` |
| `JAVA_HOME` | the `javac` on your `PATH` |

On the first build, `build.sh` creates a local signing key, `signing.keystore`. It is git-ignored: keep it if you want to install updates over an existing install, since Android only accepts updates signed with the same key.

## Setup on the phone

1. Install the APK (`./build.sh install`, or copy it over and open it).
2. Open **Album Lock** and tap **1. Povolit přístup k oznámením** (allow notification access), then turn on Album Lock.
3. Tap **2. Vybrat normální fotky zamykací obrazovky** (choose your normal lock-screen photos) and select one or more photos.
4. Play some music and lock the phone.

The switch **Zapnuto** turns the app on and off. **Vrátit normální tapetu teď** puts your normal photo back immediately. The UI is in Czech.

Until you pick at least one normal photo, the app never touches the wallpaper, because it would have no way to undo it.

## Limitations

- **Samsung wallpaper packs:** the first time a cover is set, a Samsung lock-screen "pack" (multiple photos, set from Gallery or Settings) is replaced by the photos you picked in the app. The app then does the rotation itself.
- **Position of the cover:** fixed at 32% from the top and 92% of the screen width, tuned for the S21 lock screen with a clock and no widgets. To change it, edit `COVER_TOP` and `COVER_WIDTH` in `Renderer.java`.
- **Paused music** keeps the cover for 90 seconds (`RESTORE_DELAY_MS` in `ArtListener.java`).
- Not on Google Play. Sideloaded apps on Android 13+ may need you to allow "restricted settings" for the app before notification access can be turned on (app info → ⋮ → Allow restricted settings).
