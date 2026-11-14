<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Twelve (online edition)

A music player for Android, built on [LineageOS Twelve](https://github.com/LineageOS/android_packages_apps_Twelve).
It keeps everything Twelve does with local files and media servers, and adds an online catalog, a
redesigned player and a Material 3 Expressive look.

## What's new in this edition

**Online and offline**
- One switch on the home screen: **Offline** shows only the music stored on the device (empty when
  there is none), **Online** shows songs, albums, artists and playlists from the JioSaavn catalog.
- Separate streaming quality limits for Wi-Fi and mobile data, with the quality of the current
  stream shown next to its format.
- The online home page is cached, so it opens instantly and still shows what was seen without a
  connection. Pull down to refresh it.

**Player**
- The player sits on top of the app: it opens and closes without rebuilding the screen below.
  Swipe it down to make it the mini player, swipe up on the cover for the queue.
- Lyrics synchronized word by word when available, line by line otherwise, shown in place over a
  blurred cover. The controls hide while you read and come back when you tap or pause. A lyrics
  offset can be set when a song's timings are off.
- Volume slider, AutoPlay (similar songs keep coming when the queue ends), crossfade from 0 to 12
  seconds (0 is gapless), and **Automix (beta)**, which starts the next song on a beat at the tempo
  of the one that is ending.
- The mini player sits on the blurred cover of the song, so it stays readable on any screen.

**Playlists**
- Import playlists from Spotify links (public playlists, no account needed), CSV files or plain song
  lists. The songs are matched in the online catalog.
- Share, save and import playlists of the online catalog. Delete playlists from their menu.

**Look and feel (Material 3 Expressive)**
- Material shapes for artist pictures, genre tiles and the app's own mark, with a shape change
  on press.
- Connected button groups for the Offline/Online switch, the Library tabs and the search filters.
- Grouped lists with round outer corners in every list and in Settings.
- A hero carousel on the home screen, and a play button whose shape follows the playback state.
- Album and playlist pages keep Play all, Shuffle, Play next and Add to queue in their header.

**Search**
- A start screen, filters (All, Songs, Albums, Artists, Playlists) and a clear "no results" screen.
- Tapping a song plays it. When something is already playing, a menu asks whether to play it now,
  play it next or add it to the queue.

**Widgets**
- Now playing widgets that fill with the cover and adapt their layout to their size, from 1x1 to
  4x4, including dedicated 1x1, 2x1, 2x2 and 2x4 widgets.

**Speed**
- Pages stay alive under the page on top of them, so going back is instant.
- Lighter list rows and shaped pictures drawn once instead of on every frame.

## Building

### With the ROM

Put this repository at `packages/apps/Twelve` in the source tree, add `Twelve` to
`PRODUCT_PACKAGES` of the device or product, and build as usual. `app/Android.bp` lists the
libraries; the ones that are not part of the platform are in `app/libs`. Nothing else has to be
added.

### With Gradle (Android Studio)

```
./gradlew assembleDebug
```

The debug build installs as `org.lineageos.twelve.dev`, next to a Twelve that comes with the ROM.
`./gradlew assembleRelease` builds a shrunk release APK, which needs to be signed before it can be
installed.

## Credits

### Base project

- [LineageOS Twelve](https://github.com/LineageOS/android_packages_apps_Twelve) by The LineageOS
  Project, Apache-2.0. This edition is a modification of it, under the same license.

### Services the app talks to

These are used at runtime, over the network. This project is not affiliated with any of them. Their
content belongs to their owners, and their terms apply.

- **[JioSaavn](https://www.jiosaavn.com/)**: the online catalog, streams, search, home page and
  AutoPlay suggestions.
- **[Spotify](https://open.spotify.com/)**: the public embed page of a playlist (and, when keys
  are set, the Web API) to read the names of its songs for importing. No audio comes from Spotify.
- Lyrics providers, all asked at the same time, with the best result kept:
  - [lrc.red](https://lrc.red/): word-synchronized lyrics.
  - [BetterLyrics](https://github.com/better-lyrics/better-lyrics): word-synchronized lyrics.
  - [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), through the server hosted by
    [binimum](https://github.com/binimum): syllable timings gathered from several catalogs,
    Musixmatch among them.
  - [Unison](https://unison.boidu.dev/): lyrics shared by the BetterLyrics community.
  - [LRCLIB](https://lrclib.net/): free community database of synchronized lyrics.
  - [KuGou](https://www.kugou.com/): line-synchronized lyrics.

### References studied

These projects were read to learn how something works or how it should look and feel. **No code
was copied from them**; everything here was written for this project.

- [jiosaavn-api](https://github.com/sumitkolhe/jiosaavn-api) by Sumit Kolhe (MIT): the names and
  parameters of the JioSaavn endpoints.
- [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0): how the player, the mini player,
  the lyrics screen, the volume slider, crossfade and Automix should behave.
- [am-lyrics](https://github.com/binimum/am-lyrics) (MPL-2.0): the look of Apple Music style lyrics,
  with words lighting up as they are sung.
- [Metrolist](https://github.com/mostafaalagamy/Metrolist) (GPL-3.0): how an online music app on
  Android is put together.
- [Material Design 3 Expressive](https://m3.material.io/): shapes, button groups, lists, type and
  motion.

### Looked at, not used

- [ytmusicapi](https://github.com/sigma67/ytmusicapi): YouTube Music playback would need tokens
  that are meant to stop automated clients, so it was left out.
- [Aureon](https://github.com/retrohog/Aureon) and [flac2alac](https://github.com/Drkilla/flac2alac):
  read while looking into lossless audio. JioSaavn only serves lossy streams, so lossless stays
  with local files and media servers (Subsonic, Jellyfin, Ampache).
- [PaxSenix](https://lyrics.paxsenix.org/), [SimpMusic](https://github.com/maxrave-dev/SimpMusic),
  [Megalobiz](https://www.megalobiz.com/), [Genius](https://genius.com/) and
  [Musixmatch](https://www.musixmatch.com/) (directly): considered as lyrics providers. They were
  unreachable from the app or are not supported yet (Musixmatch lyrics can still come through
  LyricsPlus).

### Libraries

- [Material Components for Android](https://github.com/material-components/material-components-android)
  (Apache-2.0): Material 3 Expressive components and the Material shapes.
- [AndroidX](https://developer.android.com/jetpack/androidx) (Apache-2.0): Media3 for playback,
  Navigation, Room, and graphics-shapes for shape drawing and morphing.
- [Coil](https://github.com/coil-kt/coil) (Apache-2.0): image loading.
- [OkHttp](https://github.com/square/okhttp) (Apache-2.0) and
  [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Apache-2.0): network
  and JSON.

## License

Apache-2.0, like LineageOS Twelve. See [LICENSES](LICENSES).
