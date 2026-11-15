"""Lyrics synchronization: finds synced lyrics (LRCLIB) for every song of the library and replaces the old ones,
only when the match is checked: same title, same artist, same length, and the timings fit inside the song."""
from __future__ import annotations

import re
import threading
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import lyrics  # MusicFlow's core/lyrics.py (LRCLIB client, file writer)

STATUS = {"running": False, "total": 0, "done": 0, "updated": 0, "kept": 0, "not_found": 0, "errors": 0,
          "current": "", "stopped": False}
_lock = threading.Lock()
_stop = threading.Event()
_TIME = re.compile(r"^\[(\d+):(\d+(?:\.\d+)?)\]", re.M)


def _last_time(synced: str) -> float:
    times = [int(m) * 60 + float(s) for m, s in _TIME.findall(synced)]
    return max(times) if times else -1


def verified(r: dict, title: str, artist: str, length_s: float) -> bool:
    """True only for the same song: synced lyrics, same title, same artist, length within 3 s, timings inside it."""
    synced = r.get("syncedLyrics") or ""
    if r.get("instrumental") or not synced or _last_time(synced) < 0:
        return False
    names = {lyrics._key(t) for t in lyrics._titles(title)}
    if lyrics._key(r.get("trackName")) not in names:
        return False
    a, ra = lyrics._key(lyrics._artist(artist)), lyrics._key(r.get("artistName"))
    if not a or not ra or not (a in ra or ra in a):
        return False
    if not length_s or not r.get("duration") or abs(float(r["duration"]) - length_s) > 3:
        return False
    return _last_time(synced) <= length_s + 2


def find(title: str, artist: str, length_s: float) -> dict | None:
    """Checked synced lyrics for this song, or None."""
    art = lyrics._artist(artist)
    if not title or not art or not length_s:
        return None
    candidates = []
    for t in lyrics._titles(title)[:2]:
        q = {"track_name": t, "artist_name": art, "duration": int(round(length_s))}
        r = lyrics._get(f"{lyrics._API}/get?" + urllib.parse.urlencode(q))
        if isinstance(r, dict):
            candidates.append(r)
        if any(verified(c, title, artist, length_s) for c in candidates):
            break
    else:
        r = lyrics._get(f"{lyrics._API}/search?" + urllib.parse.urlencode({"track_name": lyrics._titles(title)[-1], "artist_name": art}))
        if isinstance(r, list):
            candidates.extend(r[:20])
    good = [c for c in candidates if verified(c, title, artist, length_s)]
    if not good:
        return None
    best = min(good, key=lambda c: abs(float(c["duration"]) - length_s))
    synced = best["syncedLyrics"].replace("\r\n", "\n").strip()
    plain = (best.get("plainLyrics") or "").strip() or "\n".join(l.split("]", 1)[-1].strip() for l in synced.splitlines())
    return {"synced": synced, "plain": plain}


def start(tracks: list[dict], save, on_end=None) -> bool:
    """Runs in the background over these tracks. save(track, found) writes the lyrics and returns "" or a problem."""
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
            found = find(t.get("title") or "", (t.get("artists") or [t.get("artist") or ""])[0], t.get("length_s") or 0)
            if not found:
                key = "not_found"
            else:
                key = "errors" if save(t, found) else "updated"
        except Exception:
            key = "errors"
        with _lock:
            STATUS[key] += 1
            STATUS["done"] += 1

    def run():
        try:
            with ThreadPoolExecutor(4) as pool:
                list(pool.map(one, tracks))
        finally:
            STATUS.update(running=False, current="", stopped=_stop.is_set())
            if on_end:
                on_end()

    threading.Thread(target=run, daemon=True).start()
    return True


def stop() -> None:
    _stop.set()
