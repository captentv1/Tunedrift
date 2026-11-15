# Changelog

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
