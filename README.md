# GPod

An Android music player for local MP3 and audio files, with an interface modeled on the iPod classic.

[screenshot: main menu] [screenshot: Cover Flow] [screenshot: Now Playing] [screenshot: Settings / themes]

> GPod is a personal hobby project. It has been tested on a Galaxy S23 and a Galaxy S10.

## Features

### Click wheel
- Touch click wheel with haptic feedback that fires only when the highlight actually moves, not at the ends of lists
- Optional clicker sound and adjustable wheel speed
- Center button is Select; hold it to open song options
- Wheel buttons: **MENU**, **⏮**, **⏭**, **⏯**
  - Hold MENU to return to the main menu
  - Hold ⏮ / ⏭ to seek

### Main menu
- Music, Settings, Shuffle Songs, Sleep, Now Playing, Exit
- **Sleep** goes to the home screen and keeps the music playing
- **Exit** stops playback and closes the app
- Any browse list can be pinned to the main menu

### Browsing
- Cover Flow, Playlists, Artists, Albums, Songs, Genres, Folders
- When nothing is playing, ⏮ / ⏭ jump to the previous or next letter
- **Cover Flow:** 3D album covers with reflections; press the center button to flip a cover and see its tracks

### Playlists
- Create, rename (with the keyboard), delete, and add or remove songs
- Playlists already on the phone are listed too and can be copied into editable ones
- Optional "Recently Added" smart playlist

### Now Playing
- Album art, title, artist, album, track number, and a progress bar with elapsed and remaining time
- The wheel controls volume; press the center button to switch it to scrubbing
- Tap the shuffle and repeat icons to toggle them (repeat cycles Off → All → One)

### Interface
- Header shows play state and a battery icon that turns green, yellow or red by charge level, with a lightning bolt while charging
- Themes: Silver, Space Gray, and a pure-black option for AMOLED screens
- Rounded or square screen corners
- Option to hide the navigation bar
- Choose which music folders to include or exclude

### Playback
- Runs in the background with a media notification and lock-screen controls
- Pauses when headphones are unplugged
- Queue and position are restored when you reopen the app
- Swiping the app away in Recents fully stops it

### Privacy
- No play tracking
- No internet permission
- No ads, no accounts

## Install

1. Download the latest APK from the [Releases](https://github.com/KillerCatM/GPod/releases) page.
2. Allow installs from unknown sources for your browser or file manager when Android asks.
3. Open the APK and install it.

Requires Android 11 or newer.

## Build from source

### Requirements
- JDK 17
- Gradle 8.11 (the wrapper is included)
- Android Gradle Plugin 8.7
- minSdk 30 (Android 11), targetSdk 35

### Debug build

```bash
git clone https://github.com/KillerCatM/GPod.git
cd GPod
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Release signing

Release builds read signing details from an optional `keystore.properties` file in the project root. This file is not tracked by Git.

```properties
storeFile=[path/to/your.keystore]
storePassword=[store password]
keyAlias=[key alias]
keyPassword=[key password]
```

If `keystore.properties` is missing, release builds fall back to debug signing.

### Tech stack
- Kotlin
- AndroidX Media3 (ExoPlayer + MediaSessionService)
- Custom Canvas-drawn views, no XML layouts

## Permissions

| Permission | Why |
| --- | --- |
| Read audio / media (`READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` on Android 11–12) | Find and play music files on the device |
| Notifications (`POST_NOTIFICATIONS`) | Show the media playback notification |
| Vibration (`VIBRATE`) | Click wheel haptic feedback |

GPod does not request internet access.

## Disclaimer

GPod is an independent project and is not affiliated with, endorsed by, or sponsored by Apple Inc. iPod is a trademark of Apple Inc., registered in the U.S. and other countries.

## License

GPod is licensed under the [GNU General Public License v3.0](LICENSE).
