# GPod

A Local MP3 player for Android inspired by the iPod Classic UI.

[screenshot: main menu] [screenshot: Cover Flow] [screenshot: Now Playing] [screenshot: Settings / themes]

> GPod is a personal hobby project. It has been tested on a Galaxy S23 and a Galaxy S10.

## Features

### Click wheel
- Touch click wheel with haptic
- Optional clicker sound and adjustable wheel speed
- Center button is Select; hold it to open song options
- Wheel buttons: **MENU**, **⏮**, **⏭**, **⏯**
  - Tap MENU to return to the main menu
  - Tap ⏮ / ⏭ to seek
  - Tap ⏯ to play/pause

### Main menu
- Music, Settings, Shuffle Songs, Sleep, Now Playing, Exit
- **Sleep** backs out and keeps the music playing in the background
- **Exit** stops playback and closes the app
- Any browsing list in "Music" can be pinned to the main menu

### Browsing
- Cover Flow, Playlists, Artists, Albums, Songs, Genres, Folders
- When nothing is playing, ⏮ / ⏭ jump to the previous or next letter
- **Cover Flow:** 3D album covers with reflections; press the center button to flip a cover and see its tracks

### Playlists
- Create, rename, delete, and add or remove songs
- Optional "Recently Added" smart playlist

### Now Playing
- Album art, title, artist, album, track number, and a progress bar with elapsed and remaining time
- The wheel controls volume; press the center button to switch it to scrubbing
- Tap the shuffle and repeat icons to toggle them (repeat cycles Off → All → One)

### Interface
- Header shows play state and a battery icon
- Themes: Silver, Space Gray, and Black
- Option of rounded or square screen corners
- Option to hide the navigation bar
- Choose which music folders to include

### Playback
- Runs in the background with a media notification and lock-screen controls
- Pauses when headphones are unplugged
- Queue and position are restored when you reopen the app and select Now Playing

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
