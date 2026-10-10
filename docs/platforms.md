# Platforms

Where CAMusic runs and what each surface adds. [Back to the README](../README.md).

## Everywhere you listen

- **Android Auto.** A full browse tree over every configured library server, with search and
  voice, and cover art on the browse rows, served through an opaque `content://` address so the credentials inside a Subsonic or Jellyfin cover URL stay on the phone. The whole
  layout is configurable under Settings › Driving & Android Auto › Android Auto: grid or list
  rows, round artwork for artists, grouped shelves, which libraries and shelves reach the car
  and in what order, how many items a shelf loads, whether a single library gets a folder at
  all, and whether the car's transport row carries rewind and fast-forward. A track tapped in
  the car always plays on *this phone*, rather than on a speaker in another room, and for a Music Assistant track it moves the speaker selection here too, so the car's transport buttons
  address the player it just started.
- **Android Automotive.** For a car with the app installed on its own built-in head unit,
  rather than projected from a phone: a two-pane layout puts the player and the library
  side by side, sized and inset for a driver's glance rather than a phone screen.
- **Android TV.** A dedicated `tv` flavour with a D-pad Now Playing, Library, Search, Queue,
  Light Sync, onboarding and Settings, compiled from the same business logic as the phone app.
  The library opens on its categories (artists, albums, genres and the rest) above its shelves,
  albums and playlists are track lists with Play and Shuffle, and "play … on CAMusic" to the
  TV's assistant plays the match. The layout keeps inside the TV safe area, and the D-pad focus
  stays where you are: Back returns to the tile you opened.
- **LG webOS.** A native webOS television app with a ten-foot UI and multi-library playback,
  and the panel itself as the lamp: scenes, the Hue colour schemes and a luminance clamp for a
  dark room. Hue Entertainment from the TV waits for DTLS support in the webOS service (the bridge streams over DTLS), so for now the panel is the lamp, and the Hue tab says so rather than pretending to connect. See [webos/README.md](../webos/README.md).
- **Driving mode.** Large targets, swipe anywhere, and GPS speed-limit awareness from an offline
  geohashed database of 471,569 speed zones in Victoria, Australia (Transport Victoria data, CC BY 4.0) that ships inside the app. Picture-in-Picture is the
  permission-free default, with a full-width overlay behind it, triggered by the car's Bluetooth.
  The speed alert shares that trigger: it watches only while the phone is connected to the car you nominate (GPS at a fix a second is far too expensive to run on "audio is playing") and warns with a sound, a buzz and an on-screen notification at once.
- **"Hey Google, play … on CAMusic"** plays the search result rather than just opening the app.
- **Home-screen widget** with artwork and transport controls.
- **Tablets and foldables** get an adaptive grid layout.
- **Everyday touches.** An optional mini player above the tabs while you browse (Settings →
  Appearance → Now Playing & seek bar), swipe a song off the queue with an **Undo**, and
  TalkBack that can read and move the sliders, toggles and seek bars and reorder the queue.

## Installing on Android TV

Build the `tv` flavour (`./gradlew assembleTvDebug`, see [Building](building.md)) and install the APK on the TV. Releases carry the phone and tablet APK. The TV flavour brings up its own D-pad navigable UI.

## Installing on LG webOS

Package and install with the webOS CLI. The steps are in [webos/README.md](../webos/README.md).
