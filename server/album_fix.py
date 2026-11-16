"""Albums fix (Settings): finds on Deezer the album each song belongs to and writes it in the file's tags (album,
album artist, track number, year), only when the match is checked: same title, same main artist, length within 3 s.
Works without MusicFlow's module: it only needs MusicFlow's core files, which are part of the player."""
from __future__ import annotations

import re
import threading
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import artists  # MusicFlow's core/artists.py (Deezer client)
import lyrics   # title and artist helpers

STATUS = {"running": False, "total": 0, "done": 0, "updated": 0, "kept": 0, "not_found": 0, "errors": 0,
          "current": "", "stopped": False}
_lock = threading.Lock()
_stop = threading.Event()
_COMPIL = re.compile(r"\b(best of|greatest|hits|compilation|collection|essentials|anthology|playlist|now that|top \d+|vol\.? ?\d+)\b", re.I)


def _same(r: dict, titles: set, artist: str, length_s: float) -> bool:
    if lyrics._key(r.get("title_short") or r.get("title")) not in titles and lyrics._key(r.get("title")) not in titles:
        return False
    ra = lyrics._key((r.get("artist") or {}).get("name"))
    return bool(ra) and (artist in ra or ra in artist) and abs(float(r.get("duration") or 0) - length_s) <= 3


def find(title: str, artist: str, length_s: float, album: str = "") -> dict | None:
    """The checked album of this song: {"album", "album_artist", "track_info", "year"}; {} when its album is already
    right; None when nothing sure is found."""
    art = lyrics._artist(artist)
    if not title or not art or not length_s:
        return None
    titles = {lyrics._key(t) for t in lyrics._titles(title)}
    q = f'artist:"{art}" track:"{lyrics._titles(title)[-1]}"'
    res = (artists._get("/search?" + urllib.parse.urlencode({"q": q, "limit": 25})) or {}).get("data") or []
    good = [r for r in res if _same(r, titles, lyrics._key(art), length_s)]
    if not good:
        return None
    if album and any(lyrics._key((r.get("album") or {}).get("title")) == lyrics._key(album) for r in good):
        return {}
    good.sort(key=lambda r: bool(_COMPIL.search((r.get("album") or {}).get("title") or "")))
    t = artists._get(f"/track/{good[0]['id']}") or {}
    al = t.get("album") or good[0].get("album") or {}
    if not al.get("title"):
        return None
    return {"album": al["title"], "album_artist": (t.get("artist") or good[0].get("artist") or {}).get("name") or art,
            "track_info": str(t.get("track_position") or ""), "year": (t.get("release_date") or al.get("release_date") or "")[:4]}


def start(tracks: list[dict], save, on_end=None) -> bool:
    """Runs in the background over these tracks. save(track, found) writes the tags and returns "" or a problem."""
    with _lock:
        if STATUS["running"]:
            return False
        _stop.clear()
        STATUS.update(running=True, total=len(tracks), done=0, updated=0, kept=0, not_found=0, errors=0, current="", stopped=False)

    def one(t):
        if _stop.is_set():
            return
        STATUS["current"] = t.get("title") or ""
        try:
            found = find(t.get("title") or "", (t.get("artists") or [t.get("artist") or ""])[0], t.get("length_s") or 0, t.get("album") or "")
            key = "not_found" if found is None else "kept" if not found else ("errors" if save(t, found) else "updated")
        except Exception:
            key = "errors"
        with _lock:
            STATUS[key] += 1
            STATUS["done"] += 1

    def run():
        try:
            with ThreadPoolExecutor(3) as pool:
                list(pool.map(one, tracks))
        finally:
            STATUS.update(running=False, current="", stopped=_stop.is_set())
            if on_end:
                on_end()

    threading.Thread(target=run, daemon=True).start()
    return True


def stop() -> None:
    _stop.set()
