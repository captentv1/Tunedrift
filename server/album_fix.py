"""Albums fix (Settings): finds on Deezer the album each song belongs to and writes it in the file's tags (album,
album artist, track number, year, genre, and the cover when asked), only when the match is checked.

- Songs of an album (2 songs or more with the same album name): the album is searched online and its track list is
  compared with the songs; it is used when most songs are on it (title close enough, same length within 6 s).
- Other songs, and albums not found: each song is searched (same title, same main artist, length within 3 s).
Long album names ("… (Original Motion Picture Soundtrack)", "… (Deluxe Edition)") can be shortened.
Works without MusicFlow's module: it only needs MusicFlow's core files, which are part of the player."""
from __future__ import annotations

import re
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from difflib import SequenceMatcher

import artists  # MusicFlow's core/artists.py (Deezer client)
import lyrics   # title and artist helpers

STATUS = {"running": False, "total": 0, "done": 0, "updated": 0, "kept": 0, "not_found": 0, "errors": 0,
          "covers": 0, "current": "", "stopped": False}
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
            "track_info": str(t.get("track_position") or ""), "year": (t.get("release_date") or al.get("release_date") or "")[:4],
            "cover": al.get("cover_xl") or al.get("cover_big") or ""}


# Words that make an album name long without telling which album it is: soundtracks and editions.
_LONG = re.compile(r"soundtrack|motion picture|original score|music from|songs from|from the|inspired by|bande originale|"
                   r"\bost\b|\bb\.?o\.?\b|du film|de la s[ée]rie|deluxe|edition|[ée]dition|remaster|expanded|anniversary|"
                   r"bonus|version|special|collector|super|complete|\bvol\b", re.I)


def shorten(album: str) -> str:
    """"Titanic (Original Motion Picture Soundtrack)" → "Titanic"; "21 [Deluxe Edition]" → "21". Other names stay."""
    out = album or ""
    while True:
        new = re.sub(r"\s*[(\[]([^()\[\]]*)[)\]]\s*$", lambda m: "" if _LONG.search(m.group(1)) else m.group(0), out)
        new = re.sub(r"\s+[-–—:]\s+([^-–—:]*)$", lambda m: "" if _LONG.search(m.group(1)) else m.group(0), new)
        if new == out:
            return out.strip() or (album or "")
        out = new


def _sim(a: str, b: str) -> float:
    ka = {lyrics._key(t) for t in lyrics._titles(a or "")} - {""}
    kb = {lyrics._key(t) for t in lyrics._titles(b or "")} - {""}
    if not ka or not kb:
        return 0.0
    if ka & kb:
        return 1.0
    best = 0.0
    for x in ka:
        for y in kb:
            if min(len(x), len(y)) >= 4 and (x in y or y in x):
                best = max(best, 0.9)
            best = max(best, SequenceMatcher(None, x, y).ratio())
    return best


def _album(aid) -> dict:
    al = artists._get(f"/album/{aid}") or {}
    tracks = (al.get("tracks") or {}).get("data") or []
    if (al.get("nb_tracks") or 0) > len(tracks):
        tracks = artists._everything(f"/album/{aid}/tracks", 300)
    al["_tracks"] = tracks
    return al


def _compare(local: list[dict], al: dict) -> dict:
    """Which of these songs are on this album: {track id: (position, online title)}."""
    online = list(enumerate(al.get("_tracks") or [], 1))
    used, out = set(), {}
    for t in local:
        best = None
        for pos, o in online:
            if pos in used:
                continue
            s = _sim(t.get("title") or "", o.get("title_short") or o.get("title") or "")
            if s < 0.82 or t.get("length_s") and o.get("duration") and abs(float(o["duration"]) - float(t["length_s"])) > 6:
                continue
            if not best or s > best[0]:
                best = (s, pos, o)
        if best:
            used.add(best[1])
            out[t["id"]] = (best[1], best[2].get("title") or "")
    return out


def match_album(local: list[dict], album: str = "", artist: str = "", limit: int = 5) -> list[dict]:
    """The albums online that hold these songs, best first: [{"id", "album", "album_artist", "year", "genre",
    "cover", "matched", "total", "tracks": {track id: {"track_info", "title"}}}]."""
    album = album or next((t.get("album") for t in local if t.get("album")), "")
    artist = lyrics._artist(artist or next((t.get("album_artist") or t.get("artist") for t in local if t.get("album_artist") or t.get("artist")), ""))
    if not album:
        return []
    short = shorten(album)
    queries = [f'artist:"{artist}" album:"{album}"' if artist else "", f"{artist} {short}".strip(), short]
    ids: list = []
    for q in dict.fromkeys(x for x in queries if x):
        for r in (artists._get("/search/album?" + urllib.parse.urlencode({"q": q, "limit": 10})) or {}).get("data") or []:
            if r.get("id") not in ids and _sim(short, shorten(r.get("title") or "")) >= 0.6:
                ids.append(r.get("id"))
        if len(ids) >= limit:
            break
    found = []
    for aid in ids[:limit]:
        try:
            al = _album(aid)
        except Exception:
            continue
        m = _compare(local, al)
        if not m:
            continue
        found.append({"id": aid, "album": al.get("title") or "", "album_artist": (al.get("artist") or {}).get("name") or "",
                      "year": (al.get("release_date") or "")[:4], "cover": al.get("cover_xl") or al.get("cover_big") or "",
                      "genre": ((((al.get("genres") or {}).get("data") or [{}])[0]) or {}).get("name") or "",
                      "matched": len(m), "total": len(al.get("_tracks") or []), "name_score": _sim(album, al.get("title") or ""),
                      "tracks": {tid: {"track_info": str(pos), "title": title} for tid, (pos, title) in m.items()}})
    found.sort(key=lambda x: (-x["matched"], -x["name_score"], bool(_COMPIL.search(x["album"]))))
    return found


def sure(local: list[dict], m: dict) -> bool:
    """A match good enough to change the files without asking: most songs found on the album."""
    return m["matched"] >= max(2 if len(local) > 1 else 1, round(len(local) * 0.7))


def cover(url: str) -> tuple[bytes, str] | None:
    """Downloads a cover from Deezer's image servers."""
    host = urllib.parse.urlparse(url or "").hostname or ""
    if not (url.startswith("https://") and (host.endswith(".dzcdn.net") or host.endswith(".deezer.com"))):
        return None
    try:
        import musicflow_embed
        ctx = musicflow_embed._ssl_context()
    except Exception:
        ctx = None
    req = urllib.request.Request(url, headers={"User-Agent": "MusicFlowPlay"})
    with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
        data = r.read(8 * 1024 * 1024)
        mime = r.headers.get_content_type() or "image/jpeg"
    return (data, "image/png" if "png" in mime else "image/jpeg") if data else None


def start(tracks: list[dict], save, on_end=None, shorten_names: bool = False, covers: bool = False) -> bool:
    """Runs in the background over these tracks. save(track, found) writes the tags (and found["_cover"], (bytes,
    mime), when there is one) and returns "" or a problem."""
    with _lock:
        if STATUS["running"]:
            return False
        _stop.clear()
        STATUS.update(running=True, total=len(tracks), done=0, updated=0, kept=0, not_found=0, errors=0, covers=0,
                      current="", stopped=False)
    groups: dict = {}
    for t in tracks:
        if t.get("album"):
            groups.setdefault((lyrics._key(t["album"]), lyrics._key(t.get("album_artist") or "")), []).append(t)
    albums = [g for g in groups.values() if len(g) > 1]
    in_albums = {t["id"] for g in albums for t in g}
    singles = [t for t in tracks if t["id"] not in in_albums]
    cover_cache: dict = {}

    def get_cover(url):
        if url not in cover_cache:
            try:
                cover_cache[url] = cover(url)
            except Exception:
                cover_cache[url] = None
        return cover_cache[url]

    def write(t, found):
        if shorten_names and found.get("album"):
            found["album"] = shorten(found["album"])
        if not found.get("genre") or t.get("genre"):
            found.pop("genre", None)
        url = found.pop("cover", "")
        same = all(str(t.get(k) if k != "track_info" else t.get("track_no") or "") == str(v) for k, v in found.items())
        if covers and url and not t.get("cover"):
            c = get_cover(url)
            if c:
                found["_cover"] = c
        if same and "_cover" not in found:
            return "kept"
        if save(t, found):
            return "errors"
        if "_cover" in found:
            STATUS["covers"] += 1
        return "updated"

    def count(key):
        with _lock:
            STATUS[key] += 1
            STATUS["done"] += 1

    def one(t):
        if _stop.is_set():
            return
        STATUS["current"] = t.get("title") or ""
        try:
            found = find(t.get("title") or "", (t.get("artists") or [t.get("artist") or ""])[0], t.get("length_s") or 0, t.get("album") or "")
            if found is None:
                key = "not_found"
            elif not found:
                key = write(t, {"album": shorten(t["album"])}) if shorten_names and shorten(t["album"]) != t["album"] else "kept"
            else:
                key = write(t, found)
        except Exception:
            key = "errors"
        count(key)

    def group(g):
        if _stop.is_set():
            return
        STATUS["current"] = g[0].get("album") or ""
        try:
            best = next(iter(match_album(g)), None)
        except Exception:
            best = None
        if not best or not sure(g, best):
            for t in g:
                one(t)
            return
        for t in g:
            if _stop.is_set():
                return
            hit = best["tracks"].get(t["id"])
            if not hit:
                one(t)
                continue
            try:
                key = write(t, {"album": best["album"], "album_artist": best["album_artist"], "year": best["year"],
                                "track_info": hit["track_info"], "genre": best["genre"], "cover": best["cover"]})
            except Exception:
                key = "errors"
            count(key)

    def run():
        try:
            with ThreadPoolExecutor(3) as pool:
                list(pool.map(group, albums))
                list(pool.map(one, singles))
        finally:
            STATUS.update(running=False, current="", stopped=_stop.is_set())
            if on_end:
                on_end()

    threading.Thread(target=run, daemon=True).start()
    return True


def stop() -> None:
    _stop.set()
