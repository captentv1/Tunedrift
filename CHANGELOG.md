# Changelog

## 1.0.0.5 — 2026-11-17 (Android)

- **Edit a song's info** (⋯ › Edit info…, from every list, the player and album, artist and folder pages): title, artist,
  album, album artist, genre, year and track number, written in the file. "Shorten" removes "(Original Motion Picture
  Soundtrack)", "(Deluxe Edition)"… from an album name. "Find online" shows the song found online: tap it to fill in the
  fields and use its cover. Several songs at once from ⋯ on an album, artist or folder.
- **Fix an album online** (⋯ on an album › Fix this album online…): the album is searched online and its track list is
  compared with your songs; it shows how many of them are on it, then writes the album name (short or not), album
  artist, year, genre, track numbers and the cover.
- **Albums fix** (Settings › Albums) now compares whole albums with their track list online, can shorten long album
  names and add the album cover to songs without one.
- **Details** (⋯ › Details): file, folder, size, format, quality (kbps, kHz, mono/stereo), tags, cover and lyrics.

## 1.0.0.4 — 2026-11-16 (Android)

- **Home screen widget**: the song playing with its cover, and previous, play/pause and next. Tap it to open the app.

## 1.0.0.3 — 2026-11-16 (Android)

- **Artist and album pages online** (with MusicFlow installed): "All songs of …" lists the artist's popular songs and all their albums and singles; "The whole album online" lists the full album. Songs you have play, the others download one by one or all at once, with their approximate size.

## 1.0.0.2 — 2026-11-16 (Android)

- **Duplicates**: "Song (feat. B)", "Song [B]" and "Song" by the same main artist now count as the same song (remixes and live versions don't).
- **Albums fix** (Settings › Albums): finds the album each song belongs to and writes it in the file (album, album artist, track number, year). Only sure matches change; works without MusicFlow and goes on with the app closed.
- **Less heat and battery**: the glass background no longer moves, and MusicFlow's page loads only when you search online, open the Download tab or download.

## 1.0.0.1 — 2026-11-16 (Android)

- **Delete a song**: ⋯ › Delete from device removes the file (and its .lrc lyrics) after a confirmation.
- **Duplicates** (Settings): finds the songs saved more than once (same title, artist, album and length, in
  different folders), marks the best copy to keep (synced lyrics, then lossless, then the bigger file), lets you
  pick another one, and deletes the extra copies after a confirmation.

## 1.0.0.0 — 2026-11-16 (Android)

MusicFlow and MusicFlow Play become one app, with a new look.

- **One search**: the search box searches your music as you type; press Enter (or pick "Online") to also search
  online through MusicFlow, and download a song with one tap. Songs you already have say "In your music" and play.
  Choose where to search (Everywhere, My music, Online) and what (Songs, Artists, Albums, Folders). Offline, only
  your music is shown. The Download tab shows how many downloads are running.
- **MusicFlow stays out until you install it**: without it, the app is only the player (no Download tab, no
  online search). Install it from Settings › Downloads › Install MusicFlow; then online search, downloads, the
  Download tab and the download settings all appear.
- **One set of settings**: the theme and the updates of the player apply to the Download tab too (its own theme and
  update settings are gone); its download settings open from Settings › Downloads.

- **Home**: a new first tab with "Shuffle everything", the song you were listening to, Favorites, Playlists, then
  rows of albums: recently played, recently added, your folders, your artists, and a few at random.
- **Library** tab on phones: Folders, Songs, Artists, Albums and Favorites in chips at the top. The tab goes back to
  the last one you used. The bottom bar is now 4 tabs in a floating pill, as in MusicFlow.
- **Header**: the MusicFlow wordmark; on phones, search opens from the magnifier.
- **Covers**: a play button on every album, artist, folder and playlist plays it without opening it. The cover of
  the open album, artist, folder or playlist tints the background.
- Bigger headers, rounder cards, a floating player bar, a new Now playing screen, a new side panel on computers.

## 0.7.2.2 — 2026-11-16 (Test build)

Android only. Fixes updates: every build was signed with a new key, so an update was refused ("signature differs"). From this version on, builds keep the same key. Uninstall the previous version once before installing this one.

## 0.7.2.1 — 2026-11-16 (Test build)

- Android only. Same as 0.7.2, with a new version number: to try the automatic update from 0.7.2.

## 0.7.2 — 2026-11-16 (Fixes)

- **Lyrics synchronization keeps going in the background** on Android (screen off or app closed),
  with a notification showing the progress. Downloads from the Download tab already do.
- The Lyrics synchronization button is now in Settings › Library.
- Updates: the check through the player's server works on Android (its Python had no certificates).

## 0.7.1 — 2026-11-16 (Fixes, animations)

- **Fixed: tapping an artist (or the album) in the full player did nothing**, and “Go to …” in the
  player's ⋯ menu (artist, folder, album) stayed on the same page. They now open the page.
- **The artist's name in a song row opens the artist.**
- **Download tab: MusicFlow as in its own app**, full screen with its own layout, tabs and menus. A
  small folder button (top right) goes back to the library; the music keeps playing.
- **Animations**: pages slide in, menus and dialogs pop, cards and buttons react to a tap (turned
  off when the system asks for reduced motion).

## 0.7.0 — 2026-11-16 (Lyrics synchronization)

- **Lyrics synchronization** (Settings): one button finds synced lyrics (LRCLIB) for every song of the
  library and replaces the old ones: the `.lrc` next to the song and the lyrics inside the file. A
  match is used only when it is checked: same title, same artist, length within 3 seconds, and the
  last line fits inside the song. Songs without a sure match keep their lyrics. Progress is shown,
  and it can be stopped. On Android without access to all files, the player keeps the new lyrics
  itself and shows them.

## 0.6.0 — 2026-11-15 (Download tab on demand)

- **The Download tab is installed with a button**: the Android app and the Windows exe no longer
  include MusicFlow. Open the Download tab and tap “Install the Download tab”: it downloads
  MusicFlow (its server, its page and yt-dlp; on Windows also ffmpeg and selenium) from this
  version's GitHub release, then opens it. When the app updates, the Download tab updates with it.
  The apps are lighter and start faster: MusicFlow only runs once you install it.
- From the source folder (`python server/mfp_server.py`) nothing changes: MusicFlow comes from the
  submodule.

## 0.5.0 — 2026-11-15 (Folders, updates)

- **Folders view, shown first**: the library as your folders are on disk, parent folders and their
  subfolders, with the songs of each folder inside. Play or shuffle a whole folder (subfolders
  included), add it to the queue or a playlist. Search also finds folders, and a song's ⋯ menu
  has “Go to folder”. The other views (Songs, Artists, Albums, Playlists) stay as before.
- **Updates, as in MusicFlow**: the player checks for a new version at start (at most every 6 h)
  and offers it once. On Android it downloads and installs it (checked: same app, newer, same
  signature); on Windows it downloads the new exe. Can be turned off in Settings › Updates.
- **Settings sections fold**: tap a title to open or close it. Theme, Updates, Gestures and
  Keyboard start closed; the app remembers what you open.
- **Android, screen edges**: the app now draws behind the status bar and the navigation bar with
  the theme's colors (no more black bands), and keeps its content clear of them.

## 0.4.0 — 2026-11-15 (MusicFlow inside the player)

- **Download tab: MusicFlow inside the player** (PC and the Windows exe). Search by title, artist,
  link, charts, the download queue: it is MusicFlow itself (same code, from the submodule), shown
  in the player with the player's theme. The music keeps playing while you search, and the songs
  you download appear in the library on their own (MusicFlow's download folder is added to the
  player's folders).
- **Android too**: MusicFlow runs inside the app (its Android server and page, from the submodule).
  Downloads keep going in the background with their notification; voice search, links, the folder
  picker and Spotify playlists work as in MusicFlow. Songs saved in MusicFlow's folder (or the folder
  chosen in it) appear in the library.

## 0.3.0 — 2026-11-15 (themes, gestures, Windows app)

- **The 16 themes of MusicFlow** (glass, light, OLED black, classic, automatic) and a text size,
  in Settings. The phone's status bar follows light themes.
- **Gestures on the phone**: swipe up on the mini player to open it; swipe left/right for the
  next/previous song (mini player and cover); on the cover, swipe up or double tap for the lyrics,
  one tap for the next song, swipe down to close.
- **Windows app**: `MusicFlowPlay.exe` is built and published with the APK at every version.
- **Shuffle and repeat in the notification and on the lock screen** (Android), with icons that show
  the current mode.
- Android: no more empty band above the header and below the tabs.

## 0.2.1 — 2026-11-15 (Android fixes)

- **Every audio format plays on Android**: MP3, M4A, FLAC, OPUS, OGG and WAV. If a song does not
  start within a few seconds, the app reads the whole file and plays it from memory (like MusicFlow).
  A song that really can't be played now says why (error code) instead of staying stuck.
- **Choose the music folder with Android's own picker** (“Choose my music folder” on the empty
  library, and **Browse** in Settings): no more “Access denied” when browsing the storage.
  Folders on the phone and on the SD card both work.
- The app waits for its server while it starts instead of showing an error.
- Tests: the emulator now plays one song of each format at every build.

## 0.2.0 — 2026-11-15 (Android app)

- **Android app (APK).** Plays the music stored on the phone, without a computer: same page as
  on PC, shown inside the app, with the same Python server and MusicFlow `core/` running on the
  phone (Chaquopy, like MusicFlow).
- **Notification and lock-screen controls**: title, artist, cover art, previous / play-pause /
  next and the position bar; headset and Bluetooth buttons; pauses when headphones are unplugged.
  The music keeps playing with the screen off or the app in the background.
- **Folders**: `Music` and `Download` are read by default; **Browse** in Settings picks other
  folders (also on PC, where it replaces the system dialog).
- **Lyrics files**: Android only shows music files to an app, so `.lrc` files need “Allow access to
  all files” (asked in Settings, optional). Without it, lyrics come from the tags or the online search.
- The APK is built by GitHub Actions on every change and published in the repository's releases.

## 0.1.0 — 2026-11-15 (PC, and phone over Wi-Fi)

- **First version: plays the music already in your folders.** Library by songs, artists and
  albums (subfolders included), search that ignores accents, sort by title, artist, album or
  date added.
- **Player:** play/pause, previous/next, shuffle, repeat (off, all, one song), queue with
  “Play next”, “Add to queue”, reorder and remove. The queue and the position are kept when
  the page is reopened.
- **Lock-screen and notification controls** (Android, headphones buttons, PC media keys) with
  title, artist and cover art; the music keeps playing with the screen off.
- **Synced lyrics** from the `.lrc` file next to the song or from its tags; tap a line to jump
  there. “Search online” looks on LRCLIB (same search as MusicFlow) without touching the file
  unless “Save into the file” is chosen.
- **Playlists and favorites**, saved on the computer, shared by every device that opens the player.
- **`--lan`**: opens the player to phones on the same Wi-Fi, protected by a link with a key.
- Reuses MusicFlow's `core/` through a Git submodule (`musicflow/`): nothing is copied.
