"""Import: playlists from a link, the person's Spotify account (signed in on Spotify's own page, no developer key),
a YouTube channel's public playlists, another Playwave on the
same Wi-Fi (local server), or a playlist file / a list of titles. An imported playlist becomes a playlist of the
player, its songs go into the download queue (into the root folder chosen once), and the songs already on the
device are only linked, never downloaded again. A .m3u8 file is written next to the songs, so other players
(Samsung Music…) see the playlist too."""
from __future__ import annotations

import csv
import io
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from flask import Blueprint, jsonify, request

import mf_bridge
from collections_store import JsonStore
from downloads import safe_name, seconds, song_key

bp = Blueprint("importer", __name__)
CTX: dict = {}  # set by init(): queue, collections, settings, data_dir, port, accounts, imports


def init(queue, collections, settings, data_dir: Path, port: int):
    CTX.update(queue=queue, collections=collections, settings=settings, port=port,
               accounts=JsonStore(data_dir / "accounts.json", {"youtube": {}, "servers": []}),
               imports=JsonStore(data_dir / "imports.json", {"imports": []}),
               spcache=JsonStore(data_dir / "spotify-cache.json", {"playlists": [], "at": 0, "tracks": {}, "api_off": 0}))
    queue.on_song = on_song


# ---------------------------------------------------------------- HTTP helpers

def _http(method: str, url: str, headers=None, form=None, timeout=25) -> dict:
    body = urllib.parse.urlencode(form).encode() if form is not None else None
    h = {"User-Agent": "MusicFlowPlay", **(headers or {})}
    if body is not None:
        h["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=mf_bridge.ssl_context()) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        try:
            j = json.loads(detail)
            e2 = j.get("error")
            msg = j.get("error_description") or (e2.get("message") if isinstance(e2, dict) else e2)
        except Exception:
            msg = detail
        raise ImportError_(f"{e.code}: {msg or detail}", e.code) from e


class ImportError_(Exception):
    def __init__(self, msg, code=0):
        super().__init__(msg)
        self.code = code


def _acc(provider: str) -> dict:
    return CTX["accounts"].data.setdefault(provider, {})


def _save_acc():
    with CTX["accounts"].lock:
        CTX["accounts"].save()


# ---------------------------------------------------------------- Spotify: the person's own account, no key
# The phone app signs in on Spotify's own page and keeps a hidden page of Spotify's web player (SpotifyBot.kt).
# Its access token lets this code read the playlists with Spotify's Web API; if Spotify refuses, the hidden page
# reads the same lists from the web player itself.

SPOTIFY_ON_PC = "Spotify sign-in is in the phone app. On the computer, paste a Spotify playlist link (Link)."


def spotify_bot():
    """SpotifyBot of the Android app, or None (computer)."""
    try:
        from java import jclass  # Chaquopy (Android only)
        return jclass("com.musicflow.play.SpotifyBot")
    except Exception:
        return None


def spotify_state() -> dict:
    bot = spotify_bot()
    if bot is None:
        return {"available": False, "connected": False}
    try:
        return {"available": True, "connected": bool(bot.connected())}
    except Exception:
        return {"available": True, "connected": False}


def _sp_bot_connected():
    bot = spotify_bot()
    if bot is None:
        raise ImportError_(SPOTIFY_ON_PC)
    if not bot.connected():
        raise ImportError_("Connect your Spotify account first.")
    return bot


API_OFF_S = 6 * 3600


def _api_usable() -> bool:
    c = CTX.get("spcache")
    return not c or time.time() >= float(c.data.get("api_off") or 0)


def _api_failed():
    c = CTX.get("spcache")
    if c:
        c.data["api_off"] = time.time() + API_OFF_S


def _sp_api(bot, path: str) -> dict:
    token = str(bot.token(20000) or "")
    if not token:
        raise ImportError_("Spotify did not open your session. Connect again.", 401)
    return _http("GET", path if path.startswith("http") else str(bot.apiBase()) + path, {"Authorization": "Bearer " + token})


def _sp_paged(bot, path: str, limit_pages=80) -> list:
    out, url, pages = [], path, 0
    while url and pages < limit_pages:
        d = _sp_api(bot, url)
        out += d.get("items") or []
        url = d.get("next")
        pages += 1
    return out


def _sp_track(t: dict) -> dict | None:
    if not t or not t.get("name") or t.get("type", "track") != "track":
        return None
    images = (t.get("album") or {}).get("images") or []
    return {"title": t["name"], "artist": ", ".join(a.get("name", "") for a in t.get("artists") or []),
            "album": (t.get("album") or {}).get("name", ""), "duration": int((t.get("duration_ms") or 0) / 1000),
            "cover": images[0]["url"] if images else "", "url": ""}


LIKED = {"id": "liked", "name": "Liked Songs", "count": None, "image": ""}


def _sp_rows(rows) -> list[dict]:
    """Rows read from the web player's library -> the playlists (Liked Songs first)."""
    out, seen = [dict(LIKED)], set()
    for r in rows or []:
        pid = re.sub(r"[^A-Za-z0-9]", "", (r or {}).get("id") or "")
        if pid and pid not in seen:
            seen.add(pid)
            image = str(r.get("image") or "")
            out.append({"id": pid, "name": r.get("name") or "Playlist", "count": None,
                        "image": image if image.startswith("https://") else ""})
    return out


def _sp_items(d: dict) -> list[dict]:
    return [{"title": x.get("title", ""), "artist": x.get("artist", ""), "album": x.get("album", ""),
             "duration": seconds(x.get("duration")), "cover": x.get("cover", ""), "url": ""}
            for x in (d or {}).get("items") or [] if x.get("title")]


def spotify_playlists() -> list[dict]:
    bot = _sp_bot_connected()
    try:
        if not _api_usable():
            raise ImportError_("the Web API failed a moment ago")
        items = _sp_paged(bot, "/me/playlists?limit=50", limit_pages=20)
        out = [dict(LIKED)]
        for p in items:
            if not p:
                continue
            total = (p.get("tracks") or p.get("items") or {}).get("total")
            out.append({"id": p["id"], "name": p.get("name") or "Playlist", "count": total,
                        "image": ((p.get("images") or [{}])[0] or {}).get("url", "")})
        return out
    except ImportError_ as exc:
        if _api_usable():
            _api_failed()
        print(f"Spotify API refused the list ({exc}); reading the web player", flush=True)
    return _sp_rows(json.loads(str(bot.playlists(45000)) or "[]"))


def _sp_scrape(bot, pid: str) -> tuple[str, list[dict]]:
    url = str(bot.webBase()) + ("/collection/tracks" if pid == "liked" else "/playlist/" + pid)
    d = json.loads(str(bot.tracks(url, 15 * 60 * 1000)) or "{}")
    items = _sp_items(d)
    if not items:
        raise ImportError_("Spotify showed no songs for this playlist. " + (d.get("diag") or ""))
    return ("Liked Songs" if pid == "liked" else d.get("name") or "Playlist"), items


def spotify_tracks(pid: str) -> tuple[str, list[dict]]:
    pid = "liked" if pid == "liked" else re.sub(r"[^A-Za-z0-9]", "", pid)
    name, tracks = _spotify_tracks(pid)
    c = CTX.get("spcache")
    if c is not None:  # kept, so the playlist opens at once next time
        with SPC["cv"]:
            c.data.setdefault("tracks", {})[pid] = {"name": name, "tracks": tracks, "at": time.time()}
        _spc_save()
    return name, tracks


def _spotify_tracks(pid: str) -> tuple[str, list[dict]]:
    bot = _sp_bot_connected()
    try:
        if not _api_usable():
            raise ImportError_("the Web API failed a moment ago")
        if pid == "liked":
            rows = _sp_paged(bot, "/me/tracks?limit=50")
            return "Liked Songs", [x for x in (_sp_track(r.get("track")) for r in rows) if x]
        info = _sp_api(bot, f"/playlists/{pid}?fields=name")
        try:
            rows = _sp_paged(bot, f"/playlists/{pid}/tracks?limit=100")
        except ImportError_ as exc:  # newer API name of the same list
            if exc.code not in (403, 404):
                raise
            rows = _sp_paged(bot, f"/playlists/{pid}/items?limit=100")
        tracks = [x for x in (_sp_track(r.get("track") or r.get("item")) for r in rows) if x]
        if tracks:
            return info.get("name") or "Playlist", tracks
    except ImportError_ as exc:
        if exc.code == 401 or "session" in str(exc):
            _api_failed()
        print(f"Spotify API refused the songs ({exc}); reading the web player", flush=True)
    return _sp_scrape(bot, pid)


# ---------------------------------------------------------------- Spotify without waiting
# The playlists and their songs are kept on the device: the Spotify tab shows them at once, while one background
# worker reads them again (the list at most every 10 minutes, a playlist when it is opened and older than 30
# minutes). Opening the app starts it, and it then reads the songs of every playlist not read yet, one after the
# other, so opening one is instant. What a reading has found so far is shown while it runs.

LIST_FRESH_S = 10 * 60
TRACKS_FRESH_S = 30 * 60
SPC = {"cv": threading.Condition(), "task": "", "want": "", "prefetch": False, "worker": None,
       "list_try": 0.0, "error": "", "tried": {}, "errors": {}}


def _spc() -> dict:
    return CTX["spcache"].data


def _spc_save():
    c = CTX.get("spcache")
    if c is None:
        return
    with c.lock:
        try:
            tmp = c.file_path.with_suffix(".tmp")
            with SPC["cv"]:
                text = json.dumps(c.data, ensure_ascii=False, separators=(",", ":"))
            tmp.write_text(text, encoding="utf-8")
            tmp.replace(c.file_path)
        except OSError:
            pass


def _sp_next() -> str:
    """The next reading for the worker ("" = nothing to do). Called with SPC["cv"] held."""
    if not spotify_state().get("connected"):
        return ""
    c, now = _spc(), time.time()
    tracks, tried = c.setdefault("tracks", {}), SPC["tried"]
    w = SPC["want"]
    if w:
        if (w not in tracks or now - float(tracks[w].get("at") or 0) > TRACKS_FRESH_S) and now - tried.get(w, 0) > 60:
            return w
        SPC["want"] = ""
    if now - float(c.get("at") or 0) > LIST_FRESH_S and now - SPC["list_try"] > 60:
        return ":list"
    if SPC["prefetch"]:
        for p in c.get("playlists") or []:
            if p["id"] not in tracks and now - tried.get(p["id"], 0) > 3600:
                return p["id"]
    return ""


def _sp_worker():
    while True:
        with SPC["cv"]:
            task = _sp_next()
            SPC["task"] = task
            if not task:
                SPC["worker"] = None
                return
        bot = spotify_bot()
        try:
            if bot is not None:
                bot.clearPartial()
        except Exception:
            pass
        try:
            if task == ":list":
                SPC["list_try"] = time.time()
                lists = spotify_playlists()
                with SPC["cv"]:
                    _spc()["playlists"], _spc()["at"], SPC["error"] = lists, time.time(), ""
                _spc_save()
            else:
                SPC["tried"][task] = time.time()
                spotify_tracks(task)
                SPC["errors"].pop(task, None)
        except Exception as exc:
            msg = str(exc) if isinstance(exc, ImportError_) else f"{type(exc).__name__}: {exc}"
            if task == ":list":
                SPC["error"] = msg
            else:
                SPC["errors"][task] = msg
            print(f"Spotify reading {task} failed: {msg}", flush=True)
        finally:
            with SPC["cv"]:
                SPC["task"] = ""
                if SPC["want"] == task:
                    SPC["want"] = ""


def _sp_kick():
    with SPC["cv"]:
        if SPC["worker"] is None:
            SPC["worker"] = threading.Thread(target=_sp_worker, name="spotify-reader", daemon=True)
            SPC["worker"].start()


def _sp_partial():
    try:
        bot = spotify_bot()
        return json.loads(str(bot.partial()) or "null") if bot is not None else None
    except Exception:
        return None


def spotify_view(pid: str = "", warm=False, refresh=False) -> dict:
    """The Spotify tab at once: the kept playlists (and the songs of `pid`), and whether a reading is running."""
    st = spotify_state()
    if not st.get("connected"):
        return dict(st, playlists=[], running=False)
    pid = "liked" if pid == "liked" else re.sub(r"[^A-Za-z0-9]", "", pid or "")
    with SPC["cv"]:
        if warm:
            SPC["prefetch"] = True
        if refresh:
            _spc()["at"], SPC["list_try"] = 0, 0
        if pid:
            SPC["want"] = pid
            if refresh:
                SPC["tried"].pop(pid, None)
                t = _spc().get("tracks", {}).get(pid)
                if t:
                    t["at"] = 0
    _sp_kick()
    with SPC["cv"]:
        c, task, alive = _spc(), SPC["task"], SPC["worker"] is not None
        lists = list(c.get("playlists") or [])
        kept = (c.get("tracks") or {}).get(pid) if pid else None
        kept = dict(kept, tracks=[dict(x) for x in kept["tracks"]]) if kept else None
        want = SPC["want"]
    out = dict(st, playlists=lists, running=task == ":list" or (not lists and alive and not SPC["error"]),
               error="" if lists else SPC["error"])
    if task == ":list" and not lists:
        rows = _sp_partial()
        if isinstance(rows, list):
            out["playlists"] = _sp_rows(rows)
    if pid:
        reading = task == pid or (want == pid and alive)
        if kept:
            name, tracks, complete = kept.get("name") or "Playlist", kept["tracks"], True
        else:
            d = _sp_partial() if task == pid else None
            d = d if isinstance(d, dict) else {}
            name = d.get("name") or next((p["name"] for p in lists if p["id"] == pid), "Playlist")
            tracks, complete = _sp_items(d), False
        tracks = _with_local(tracks)
        out.update(pid=pid, name=name, tracks=tracks, have=sum(1 for t in tracks if t["have"]), complete=complete,
                   tracks_running=reading, tracks_error="" if (tracks or reading) else SPC["errors"].get(pid, ""))
    return out


# ---------------------------------------------------------------- YouTube: a channel's public playlists, no key

_YT_NOISE = re.compile(r"\s*[\(\[](official|lyrics?|audio|video|clip|music video|hd|hq|4k|visualizer|paroles)[^\)\]]*[\)\]]", re.I)


def youtube_song(title_text: str, channel: str) -> tuple[str, str]:
    """"Artist - Title (Official Video)" -> ("Title", "Artist"); otherwise the channel is the artist."""
    t = _YT_NOISE.sub("", title_text or "").strip()
    if " - " in t:
        artist_name, song = [x.strip() for x in t.split(" - ", 1)]
        return song, artist_name
    channel = re.sub(r"\s*-\s*Topic$|VEVO$", "", channel or "").strip()
    return t, channel


def youtube_channel(text: str) -> str:
    """@name, a channel link or a YouTube Music channel link -> https://www.youtube.com/<channel>."""
    t = (text or "").strip()
    if not t:
        raise ImportError_("Paste your YouTube channel link or @name.")
    if "://" not in t and not t.startswith(("www.", "youtube.com", "m.youtube.com", "music.youtube.com")):
        return "https://www.youtube.com/@" + urllib.parse.quote(t.lstrip("@"))
    u = urllib.parse.urlsplit(t if "://" in t else "https://" + t)
    if not (u.hostname or "").endswith("youtube.com"):
        raise ImportError_("This is not a YouTube channel link.")
    m = re.match(r"/(@[^/]+|channel/[\w-]+|c/[^/]+|user/[^/]+)", u.path)
    if not m:
        raise ImportError_("Paste a channel link (youtube.com/@name). For one playlist, use Link.")
    return "https://www.youtube.com/" + m.group(1)


def _ydl(url: str) -> dict:
    try:
        import yt_dlp
    except Exception as exc:
        raise ImportError_(f"yt-dlp is missing ({exc}).") from exc
    opts = {"quiet": True, "no_warnings": True, "extract_flat": "in_playlist", "skip_download": True,
            "playlistend": 5000, "noprogress": True}
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            return y.extract_info(url, download=False) or {}
    except Exception as exc:
        raise ImportError_(f"YouTube: {str(exc).replace('ERROR: ', '')[:200]}", 502) from exc


def _thumb(e: dict) -> str:
    th = e.get("thumbnails") or []
    return (th[-1] or {}).get("url", "") if th else (e.get("thumbnail") or "")


def youtube_playlists(channel_text: str) -> tuple[str, list[dict]]:
    channel = youtube_channel(channel_text)
    info = _ydl(channel + "/playlists")
    out = []
    for e in info.get("entries") or []:
        pid = e.get("id") or ""
        if not pid or e.get("ie_key") not in (None, "YoutubeTab") or not re.fullmatch(r"[\w-]{10,}", pid):
            continue
        out.append({"id": pid, "name": e.get("title") or "Playlist", "count": e.get("playlist_count"), "image": _thumb(e)})
    if not out:
        raise ImportError_("This channel has no public playlist.")
    return channel, out


def youtube_tracks(pid: str) -> tuple[str, list[dict]]:
    pid = re.sub(r"[^A-Za-z0-9_-]", "", pid)
    info = _ydl("https://www.youtube.com/playlist?list=" + pid)
    out = []
    for e in info.get("entries") or []:
        vid = e.get("id")
        if not vid or e.get("title") in ("[Private video]", "[Deleted video]", "Private video", "Deleted video"):
            continue
        song, art = youtube_song(e.get("title"), e.get("channel") or e.get("uploader"))
        out.append({"title": song, "artist": art, "duration": seconds(e.get("duration")), "cover": _thumb(e),
                    "url": f"https://www.youtube.com/watch?v={vid}"})
    return info.get("title") or "Playlist", out


# ---------------------------------------------------------------- other Playwave (local server)

def parse_server(address: str) -> tuple[str, str]:
    """The link printed by `run_lan.bat` on the computer: http://192.168.1.20:5091/?t=…"""
    u = urllib.parse.urlsplit(address.strip() if "://" in address else "http://" + address.strip())
    if u.scheme not in ("http", "https") or not u.hostname:
        raise ImportError_("Paste the link shown by Playwave on the computer (http://…:5091/?t=…).")
    token = (urllib.parse.parse_qs(u.query).get("t") or [""])[0]
    if not token:
        raise ImportError_("The link has no key (?t=…). Copy the whole link.")
    return f"{u.scheme}://{u.netloc}", token


def _remote(base: str, token: str, path: str) -> dict:
    sep = "&" if "?" in path else "?"
    req = urllib.request.Request(f"{base}{path}{sep}t={urllib.parse.quote(token)}", headers={"User-Agent": "MusicFlowPlay"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise ImportError_("The other device refused (wrong key?)." if e.code == 403 else f"Error {e.code} from the other device.", e.code)
    except OSError as e:
        raise ImportError_(f"Cannot reach the other device ({e}). Same Wi-Fi? Is it started with run_lan.bat?")


def server_playlists(base: str, token: str) -> list[dict]:
    col = _remote(base, token, "/api/collections")
    tracks = _remote(base, token, "/api/tracks").get("tracks") or []
    out = [{"id": "all", "name": "All songs", "count": len(tracks)},
           {"id": "favorites", "name": "Favorites", "count": len(col.get("favorites") or [])}]
    out += [{"id": p["id"], "name": p.get("name") or "Playlist", "count": len(p.get("tracks") or [])} for p in col.get("playlists") or []]
    return out


def server_tracks(base: str, token: str, pid: str) -> tuple[str, list[dict]]:
    col = _remote(base, token, "/api/collections")
    tracks = {t["id"]: t for t in _remote(base, token, "/api/tracks").get("tracks") or []}
    if pid == "all":
        name, ids = "All songs", list(tracks)
    elif pid == "favorites":
        name, ids = "Favorites", col.get("favorites") or []
    else:
        p = next((p for p in col.get("playlists") or [] if p["id"] == pid), None)
        if not p:
            raise ImportError_("This playlist is no longer on the other device.")
        name, ids = p.get("name") or "Playlist", p.get("tracks") or []
    out = []
    q = urllib.parse.quote(token)
    for tid in ids:
        t = tracks.get(tid)
        if not t:
            continue
        out.append({"title": t.get("title") or "", "artist": t.get("artist") or "", "album": t.get("album") or "",
                    "duration": int(t.get("length_s") or 0), "ext": t.get("format") or "mp3",
                    "file_url": f"{base}/api/audio/{tid}?t={q}"})
    return name, out


# ---------------------------------------------------------------- playlist files and lists of titles

def parse_text(text_value: str, filename: str = "") -> tuple[str, list[dict]]:
    """M3U/M3U8, CSV (e.g. Exportify's export of Spotify playlists) or one "Artist - Title" per line."""
    name = Path(filename).stem if filename else "Imported list"
    text_value = (text_value or "").lstrip("﻿")
    first = text_value.splitlines()[0] if text_value.strip() else ""
    out = []
    if filename.lower().endswith(".csv") or ("," in first and re.search(r"track|title|titre|artist", first, re.I)):
        rows = csv.DictReader(io.StringIO(text_value))
        cols = {c.lower(): c for c in rows.fieldnames or []}
        pick = lambda *names: next((cols[n] for n in names if n in cols), None)
        ct, ca = pick("track name", "title", "titre", "name", "song"), pick("artist name(s)", "artist", "artists", "artiste")
        cal, cd = pick("album name", "album"), pick("duration (ms)", "duration", "durée")
        for r in rows:
            title_text = (r.get(ct) or "").strip() if ct else ""
            if not title_text:
                continue
            d = (r.get(cd) or "").strip() if cd else ""
            dur = int(int(d) / 1000) if d.isdigit() and cd and "ms" in cd.lower() else seconds(d)
            out.append({"title": title_text, "artist": (r.get(ca) or "").replace(";", ", ").strip() if ca else "",
                        "album": (r.get(cal) or "").strip() if cal else "", "duration": dur})
        return name, out
    extinf = None
    for line in text_value.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.upper().startswith("#EXTINF"):
            m = re.match(r"#EXTINF:\s*(-?\d+)[^,]*,(.*)", line, re.I)
            extinf = (int(m.group(1)), m.group(2).strip()) if m else None
            continue
        if line.startswith("#"):
            continue
        if extinf:
            dur, label = extinf
            extinf = None
        else:
            dur, label = 0, Path(line.replace("\\", "/")).stem if re.search(r"\.(mp3|flac|m4a|opus|ogg|wav|webm)$", line, re.I) else line
        if " - " in label:
            artist_name, title_text = [x.strip() for x in label.split(" - ", 1)]
        else:
            artist_name, title_text = "", label
        out.append({"title": title_text, "artist": artist_name, "duration": max(0, dur)})
    return name, out


# ---------------------------------------------------------------- imports

def _imports() -> list[dict]:
    return CTX["imports"].data.setdefault("imports", [])


def _save_imports():
    with CTX["imports"].lock:
        CTX["imports"].save()


def fetch(source: dict) -> tuple[str, list[dict]]:
    kind = source.get("kind")
    if kind == "link":
        code, r = mf_bridge.call("resolve-link", {"url": source.get("url", "")})
        if code != 200:
            raise ImportError_(r.get("error") or "This link could not be read.")
        items = []
        for x in r.get("results") or []:
            items.append({"title": x.get("title") or "", "artist": x.get("uploader") or x.get("artist") or "",
                          "album": x.get("album") or "", "duration": seconds(x.get("duration")),
                          "cover": x.get("thumbnail") or "", "url": x.get("url") or ""})
        return r.get("playlist_name") or "Playlist", items
    if kind == "spotify":
        return spotify_tracks(source.get("id", ""))
    if kind == "youtube":
        return youtube_tracks(source.get("id", ""))
    if kind == "server":
        return server_tracks(source.get("base", ""), source.get("token", ""), source.get("id", ""))
    if kind == "text":
        return parse_text(source.get("text", ""), source.get("filename", ""))
    raise ImportError_("Unknown source.")


def _with_local(tracks: list[dict]) -> list[dict]:
    lib = CTX["queue"].lib
    for t in tracks:
        have = lib.find(t.get("title"), t.get("artist"), seconds(t.get("duration"))) if t.get("title") else None
        t["have"] = have["id"] if have else ""
    return tracks


def start_import(source: dict, name: str, tracks: list[dict], root: str, import_id: str = "") -> dict:
    conf = CTX["queue"].conf()
    root = root or conf.get("root") or mf_bridge.default_dest()
    if not root:
        raise ImportError_("Choose the root folder first.")
    imp = next((i for i in _imports() if i["id"] == import_id), None) if import_id else None
    if imp is None and source.get("kind") != "text":
        # The same playlist imported again: the same import is updated (no second playlist).
        same = lambda s: all(str(s.get(k) or "") == str(source.get(k) or "") for k in ("kind", "id", "url", "base"))
        imp = next((i for i in _imports() if same(i.get("source") or {})), None)
    if imp is None:
        folder = str(Path(root) / safe_name(name)) if conf.get("subfolders", True) else root
        pl = CTX["collections"].create(name, [])
        imp = {"id": uuid.uuid4().hex[:12], "name": name, "source": {k: v for k, v in source.items() if k != "text"},
               "folder": folder, "playlist": pl["id"], "keys": [], "tracks": {}, "created": int(time.time())}
        _imports().append(imp)
    keys = [song_key(t.get("title"), t.get("artist")) for t in tracks]
    imp["keys"] = list(dict.fromkeys(imp.get("keys", []) + keys)) if source.get("kind") == "text" else list(dict.fromkeys(keys))
    imp["count"] = len(imp["keys"])
    imp["last"] = int(time.time())
    if source.get("kind") == "server":  # keep the address (and its key) to update later
        imp["source"] = {k: source.get(k) for k in ("kind", "base", "token", "id")}
    _save_imports()
    Path(imp["folder"]).mkdir(parents=True, exist_ok=True)
    added = CTX["queue"].add(tracks, imp["folder"], imp["id"])
    return dict(imp, queued=len(added))


def on_song(item: dict):
    """A song of an import is on the device: put it in the playlist, in the order of the source, and write the .m3u8."""
    if not item.get("import") or not item.get("track"):
        return
    imp = next((i for i in _imports() if i["id"] == item["import"]), None)
    if not imp:
        return
    imp.setdefault("tracks", {})[item["key"]] = item["track"]
    imp.setdefault("paths", {})[item["key"]] = item.get("path", "")
    ordered = [imp["tracks"][k] for k in imp["keys"] if k in imp["tracks"]]
    if not CTX["collections"].update(imp["playlist"], tracks=ordered):
        imp["playlist"] = CTX["collections"].create(imp["name"], ordered)["id"]
    _save_imports()
    if CTX["queue"].conf().get("m3u", True):
        write_m3u(imp)


def write_m3u(imp: dict):
    folder = Path(imp["folder"])
    lines = ["#EXTM3U"]
    for k in imp["keys"]:
        p = (imp.get("paths") or {}).get(k)
        if not p or not Path(p).is_file():
            continue
        try:
            rel = os.path.relpath(p, folder)
        except ValueError:  # another drive (Windows)
            rel = p
        lines.append(rel.replace("\\", "/") if os.name != "nt" else rel)
    try:
        (folder / f"{safe_name(imp['name'])}.m3u8").write_text("\n".join(lines) + "\n", encoding="utf-8")
    except OSError:
        pass


# ---------------------------------------------------------------- routes

def _err(exc, code=400):
    return jsonify({"error": str(exc)}), code


@bp.route("/api/import/state")
def api_state():
    acc = CTX["accounts"].data
    out = {"servers": acc.get("servers", []), "spotify": spotify_state(),
           "youtube": {"channel": (acc.get("youtube") or {}).get("channel", "")},
           "imports": [{k: i.get(k) for k in ("id", "name", "source", "folder", "playlist", "count", "last")} | {"have": len(i.get("tracks") or {})}
                       for i in reversed(_imports())],
           "musicflow": mf_bridge.STATE, "default_root": mf_bridge.default_dest()}
    for i in out["imports"]:
        if (i.get("source") or {}).get("token"):
            i["source"] = {k: v for k, v in i["source"].items() if k != "token"}
    return jsonify(out)


@bp.route("/api/import/spotify")
def api_spotify():
    a = request.args
    return jsonify(spotify_view(a.get("pid", ""), warm=bool(a.get("warm")), refresh=bool(a.get("refresh"))))


@bp.route("/api/import/account", methods=["POST"])
def api_account():
    """Forgets the YouTube channel (Spotify's session is in the phone app: MFPNative.spotifyLogout())."""
    d = request.get_json(silent=True) or {}
    if d.get("provider") != "youtube":
        return _err("Unknown service.", 404)
    _acc("youtube").pop("channel", None)
    _save_acc()
    return jsonify({"ok": True})


@bp.route("/api/import/playlists", methods=["POST"])
def api_playlists():
    d = request.get_json(silent=True) or {}
    try:
        kind = d.get("kind")
        if kind == "spotify":
            return jsonify({"playlists": spotify_playlists()})
        if kind == "youtube":
            channel, lists = youtube_playlists(d.get("address") or _acc("youtube").get("channel", ""))
            _acc("youtube")["channel"] = channel
            _save_acc()
            return jsonify({"playlists": lists, "channel": channel})
        if kind == "server":
            base, token = parse_server(d.get("address", ""))
            lists = server_playlists(base, token)
            servers = CTX["accounts"].data.setdefault("servers", [])
            if base not in [s.get("base") for s in servers]:
                servers.append({"base": base})
            _remember_token(base, token)
            return jsonify({"playlists": lists, "base": base})
        return _err("Unknown source.")
    except ImportError_ as exc:
        return _err(exc, 502 if exc.code else 400)
    except Exception as exc:
        return _err(f"{type(exc).__name__}: {exc}", 502)


def _remember_token(base: str, token: str):
    for s in CTX["accounts"].data.setdefault("servers", []):
        if s.get("base") == base:
            s["token"] = token
    _save_acc()


def _source(d: dict) -> dict:
    s = dict(d.get("source") or {})
    if s.get("kind") == "server" and not s.get("token"):
        if s.get("address"):
            s["base"], s["token"] = parse_server(s["address"])
        else:
            s["token"] = next((x.get("token", "") for x in CTX["accounts"].data.get("servers", []) if x.get("base") == s.get("base")), "")
    return s


@bp.route("/api/import/preview", methods=["POST"])
def api_preview():
    d = request.get_json(silent=True) or {}
    try:
        name, tracks = fetch(_source(d))
    except ImportError_ as exc:
        return _err(exc, 502 if exc.code else 400)
    except Exception as exc:
        return _err(f"{type(exc).__name__}: {exc}", 502)
    tracks = _with_local(tracks)
    return jsonify({"name": name, "tracks": tracks, "have": sum(1 for t in tracks if t["have"])})


@bp.route("/api/import/start", methods=["POST"])
def api_start():
    d = request.get_json(silent=True) or {}
    try:
        source = _source(d)
        tracks = d.get("tracks")
        name = str(d.get("name") or "").strip()
        if not isinstance(tracks, list):
            name2, tracks = fetch(source)
            name = name or name2
        if not tracks:
            return _err("No songs in this playlist.")
        return jsonify({"import": start_import(source, name or "Playlist", tracks, str(d.get("root") or ""))})
    except ImportError_ as exc:
        return _err(exc, 502 if exc.code else 400)
    except Exception as exc:
        return _err(f"{type(exc).__name__}: {exc}", 502)


@bp.route("/api/import/<iid>", methods=["POST", "DELETE"])
def api_one(iid):
    imp = next((i for i in _imports() if i["id"] == iid), None)
    if not imp:
        return _err("Import not found.", 404)
    if request.method == "DELETE":  # forgets the import; the songs and the playlist stay
        CTX["imports"].data["imports"] = [i for i in _imports() if i["id"] != iid]
        _save_imports()
        return jsonify({"ok": True})
    if imp["source"].get("kind") == "text":
        return _err("A list of titles cannot be updated: import it again.")
    try:
        source = dict(imp["source"])
        if source.get("kind") == "server" and not source.get("token"):
            source = _source({"source": source})
        _, tracks = fetch(source)
        return jsonify({"import": start_import(source, imp["name"], tracks, "", iid)})
    except ImportError_ as exc:
        return _err(exc, 502 if exc.code else 400)
    except Exception as exc:
        return _err(f"{type(exc).__name__}: {exc}", 502)
