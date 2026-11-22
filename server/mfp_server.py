from __future__ import annotations

import argparse
import hmac
import io
import json
import mimetypes
import os
import secrets
import shutil
import socket
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import paths

paths.use_core()

from flask import Flask, jsonify, request, send_file

import backup
import discovery
import downloads
import importer
import lyrics
import mf_bridge
from collections_store import Collections, JsonStore, Plays
from library_index import LibraryIndex, cover_bytes, file_lyrics, plain_from_synced

PORT = 5091
RELEASES = "https://github.com/captentv1/musicflow-play/releases"
VERSION = "1.2.5.0"
DATA_DIR = paths.data_dir()
DEFAULT_FOLDERS = [f for f in os.environ.get("MFP_DEFAULT_FOLDERS", "").split(os.pathsep) if f] or [str(Path.home() / "Music")]
BROWSE_ROOTS = [f for f in os.environ.get("MFP_BROWSE_ROOTS", "").split(os.pathsep) if f]
_AUDIO_TYPES = {".mp3": "audio/mpeg", ".flac": "audio/flac", ".wav": "audio/wav", ".m4a": "audio/mp4",
                ".mp4": "audio/mp4", ".opus": "audio/ogg", ".ogg": "audio/ogg", ".webm": "audio/webm"}

app = Flask(__name__, static_folder=None)
settings = JsonStore(DATA_DIR / "settings.json", {"folders": list(DEFAULT_FOLDERS), "lan_token": ""})
index = LibraryIndex(DATA_DIR / "library-index.json")
collections = Collections(DATA_DIR / "collections.json")
plays = Plays(DATA_DIR / "plays.json")
disc_store = JsonStore(DATA_DIR / "discovery.json", {"daily": {}, "releases": {}})
releases = discovery.Releases(disc_store)
lyrics_cache = JsonStore(DATA_DIR / "lyrics-online.json", {})
queue = downloads.Queue(DATA_DIR / "downloads.json", index, settings)
importer.init(queue, collections, settings, DATA_DIR, PORT)
app.register_blueprint(importer.bp)

CONFIG = {"lan": False, "token": secrets.token_urlsafe(32), "token_in_page": True}
_LOCAL_HOSTS = {f"127.0.0.1:{PORT}", f"localhost:{PORT}"}


def _is_local_request() -> bool:
    return request.host in _LOCAL_HOSTS and request.remote_addr in ("127.0.0.1", "::1")


# Security: every /api/ call needs the token (header, or ?t= for <audio>/<img> URLs). Without --lan only
# local Hosts are served (blocks other sites and DNS rebinding); with --lan the token is the only key.
@app.before_request
def _protect_api():
    if not CONFIG["lan"] and request.host not in _LOCAL_HOSTS:
        return "Forbidden", 403
    if request.path.startswith("/api/"):
        given = request.headers.get("X-MFP-Token") or request.args.get("t") or ""
        if not hmac.compare_digest(given, CONFIG["token"]):
            return jsonify({"error": "Forbidden"}), 403


@app.after_request
def _security_headers(resp):
    resp.headers.setdefault("X-Frame-Options", "DENY")
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    return resp


@app.route("/")
def page():
    html = (paths.WEB_DIR / "index.html").read_text(encoding="utf-8")
    # Security: the token is written into the page only for this computer; a phone gets it from the ?t= link.
    if CONFIG["token_in_page"] and _is_local_request():
        html = html.replace("<head>", f"<head><script>window.MFP_TOKEN='{CONFIG['token']}'</script>", 1)
    resp = app.response_class(html, mimetype="text/html")
    resp.headers["Cache-Control"] = "no-store"
    return resp


@app.route("/manifest.webmanifest")
def manifest():
    return app.response_class((paths.WEB_DIR / "manifest.webmanifest").read_text(encoding="utf-8"),
                              mimetype="application/manifest+json")


@app.route("/icon.svg")
def icon():
    return send_file(paths.WEB_DIR / "icon.svg", mimetype="image/svg+xml", max_age=86400)


def _scan_async():
    threading.Thread(target=index.scan, args=(list(settings.data["folders"]),), daemon=True).start()


@app.route("/api/state")
def api_state():
    return jsonify({"version": VERSION, "folders": settings.data["folders"], "scan": index.status,
                    "count": len(index.tracks), "lan": CONFIG["lan"], "local": _is_local_request(),
                    "musicflow": dict(mf_bridge.STATE) if _is_local_request() else None})


# ---- MusicFlow inside the player: its searches, and the download queue (see downloads.py and importer.py) ----

@app.route("/api/mf/<path:sub>", methods=["GET", "POST"])
def api_mf(sub):
    """Online search, links, artists… answered by MusicFlow's code in this process (no second page)."""
    if not _is_local_request() or not mf_bridge.allowed(sub):
        return jsonify({"error": "Not available."}), 403
    payload = request.get_json(silent=True) or {}
    code, data = mf_bridge.search(payload) if sub == "search" and request.method == "POST" else mf_bridge.call(sub, payload, request.method)
    return jsonify(data), code


@app.route("/api/dl")
def api_dl():
    return jsonify(queue.snapshot())


@app.route("/api/dl/add", methods=["POST"])
def api_dl_add():
    data = request.get_json(silent=True) or {}
    songs = [s for s in data.get("songs") or [] if isinstance(s, dict)][:5000]
    if not songs:
        return jsonify({"error": "Nothing to download."}), 400
    added = queue.add(songs, str(data.get("folder") or ""))
    return jsonify({"added": len(added), "skipped": len(songs) - len(added)})


@app.route("/api/dl/control", methods=["POST"])
def api_dl_control():
    data = request.get_json(silent=True) or {}
    action = str(data.get("action") or "")
    if action not in ("pause", "resume", "retry", "stop", "clear", "remove"):
        return jsonify({"error": "Unknown action."}), 400
    queue.control(action, str(data.get("id") or ""))
    return jsonify(queue.snapshot())


@app.route("/api/dl/settings", methods=["POST"])
def api_dl_settings():
    data = request.get_json(silent=True) or {}
    conf = dict(settings.data.get("dl") or {})
    if "root" in data:
        root = str(data.get("root") or "").strip()
        if root:
            try:
                Path(root).mkdir(parents=True, exist_ok=True)
            except OSError as exc:
                return jsonify({"error": f"This folder can't be used: {exc.strerror or exc}"}), 400
            # The songs downloaded there appear in the library.
            if not any(_inside(Path(root), Path(f)) for f in settings.data["folders"]):
                settings.data["folders"] = settings.data["folders"] + [root]
        conf["root"] = root
    if data.get("format") in ("mp3-320", "mp3-192", "mp3-128", "flac", "opus", "aac", "wav"):
        conf["format"] = data["format"]
    if "parallel" in data:
        conf["parallel"] = max(1, min(4, int(data.get("parallel") or 1)))
    for k in ("subfolders", "m3u", "lyrics"):
        if k in data:
            conf[k] = bool(data[k])
    settings.data["dl"] = conf
    settings.save()
    return jsonify(queue.snapshot())


@app.route("/api/latest-version")
def api_latest_version():
    """Latest release, read from GitHub's redirect when its API refuses this network (it limits checks)."""
    import re
    import urllib.error
    import urllib.request

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    location = ""
    try:
        req = urllib.request.Request(RELEASES + "/latest", headers={"User-Agent": "MusicFlowPlay"})
        # Android's Python has no certificates of its own.
        urllib.request.build_opener(_NoRedirect, urllib.request.HTTPSHandler(context=mf_bridge.ssl_context())).open(req, timeout=15).close()
    except urllib.error.HTTPError as exc:
        location = exc.headers.get("Location", "") if exc.code in (301, 302, 303, 307, 308) else ""
    except Exception as exc:
        return jsonify({"error": f"{type(exc).__name__}: {exc}"}), 502
    tag = location.rstrip("/").rsplit("/", 1)[-1]
    if not re.fullmatch(r"v?\d+(\.\d+)*", tag):
        return jsonify({"error": "No release found."}), 502
    return jsonify({"tag_name": tag, "body": "", "assets": [
        {"name": n, "url": f"{RELEASES}/download/{tag}/{n}"} for n in ("MusicFlowPlay.apk", "MusicFlowPlay.exe")]})


@app.route("/api/folders", methods=["POST"])
def api_folders():
    data = request.get_json(silent=True) or {}
    folders = [str(Path(str(f).strip()).expanduser()) for f in data.get("folders") or [] if str(f).strip()]
    missing = [f for f in folders if not Path(f).is_dir()]
    if missing:
        return jsonify({"error": "Folder not found: " + missing[0]}), 404
    settings.data["folders"] = list(dict.fromkeys(folders))
    settings.save()
    _scan_async()
    return jsonify({"folders": settings.data["folders"]})


def _roots() -> list[str]:
    if BROWSE_ROOTS:
        return BROWSE_ROOTS
    if os.name == "nt":
        return [f"{c}:\\" for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ" if Path(f"{c}:\\").exists()]
    return list(dict.fromkeys([str(Path.home()), "/"]))


@app.route("/api/browse")
def api_browse():
    wanted = (request.args.get("path") or "").strip()
    if not wanted:
        return jsonify({"path": "", "parent": None, "folders": [{"name": r, "path": r} for r in _roots()]})
    current = Path(wanted).expanduser()
    if not current.is_dir():
        return jsonify({"error": "Folder not found."}), 404
    folders = []
    try:
        for d in sorted(current.iterdir(), key=lambda x: x.name.lower()):
            if d.is_dir() and not d.name.startswith((".", "$")):
                folders.append({"name": d.name, "path": str(d)})
    except OSError as exc:
        return jsonify({"error": f"This folder can't be opened: {exc.strerror or exc}"}), 403
    parent = "" if str(current) in _roots() or current.parent == current else str(current.parent)
    return jsonify({"path": str(current), "parent": parent, "folders": folders[:500]})


@app.route("/api/scan", methods=["POST"])
def api_scan():
    _scan_async()
    return jsonify({"scan": index.status})


@app.route("/api/tracks")
def api_tracks():
    keep = ("id", "title", "artist", "artists", "album", "album_artist", "genre", "year", "track_no",
            "length_s", "cover", "lyrics", "lrc", "format", "mtime", "path", "size_bytes")
    return jsonify({"tracks": [{k: t.get(k) for k in keep} for t in index.all()], "scan": index.status})


@app.route("/api/tracks/<tid>", methods=["DELETE"])
def api_delete_track(tid):
    """Deletes a song's file from the device (and its .lrc lyrics file next to it). It cannot be undone."""
    t, err = _track_or_404(tid)
    if err:
        return err
    f = Path(t["path"])
    try:
        f.unlink()
    except PermissionError:
        return jsonify({"error": "The app is not allowed to delete this file.", "needs_access": True}), 403
    except OSError as exc:
        return jsonify({"error": f"Could not delete the file: {exc}"}), 500
    lrc = f.with_suffix(".lrc")
    if lrc.is_file():
        try:
            lrc.unlink()
        except OSError:
            pass
    index.remove(tid)
    return jsonify({"ok": True})


def _track_or_404(tid):
    t = index.get(tid)
    if not t:
        return None, (jsonify({"error": "Track not found."}), 404)
    return t, None


@app.route("/api/audio/<tid>")
def api_audio(tid):
    t, err = _track_or_404(tid)
    if err:
        return err
    f = Path(t["path"])
    mime = _AUDIO_TYPES.get(f.suffix.lower()) or mimetypes.guess_type(f.name)[0] or "application/octet-stream"
    return send_file(f, mimetype=mime, conditional=True)


@app.route("/api/cover/<tid>")
def api_cover(tid):
    t, err = _track_or_404(tid)
    if err:
        return err
    found = cover_bytes(t["path"])
    if not found:
        return jsonify({"error": "No cover art."}), 404
    data, mime = found
    resp = send_file(io.BytesIO(data), mimetype=mime, max_age=86400)
    resp.headers["Cache-Control"] = "private, max-age=86400"
    return resp


@app.route("/api/lyrics/<tid>")
def api_lyrics(tid):
    t, err = _track_or_404(tid)
    if err:
        return err
    found = file_lyrics(t["path"])
    source = "file" if (found["synced"] or found["plain"]) else ""
    online = lyrics_cache.data.get(tid)
    if online and (online.get("verified") or not found["synced"] and online.get("synced") or not source):
        found, source = {"synced": online.get("synced") or "", "plain": online.get("plain") or ""}, "online"
    return jsonify(dict(found, source=source))


@app.route("/api/lyrics/<tid>/find", methods=["POST"])
def api_lyrics_find(tid):
    t, err = _track_or_404(tid)
    if err:
        return err
    artist = (t.get("artists") or [""])[0]
    try:
        found = lyrics.find_items(t["title"], artist, t.get("length_s") or 0)
    except Exception as exc:
        return jsonify({"error": f"Lyrics search failed: {exc}"}), 502
    if not found:
        return jsonify({"error": "No lyrics found for this song."}), 404
    entry = {"synced": found.get("synced") or "", "plain": found.get("plain") or plain_from_synced(found.get("synced") or "")}
    with lyrics_cache.lock:
        lyrics_cache.data[tid] = entry
        lyrics_cache.save()
    if (request.get_json(silent=True) or {}).get("save"):
        problem = lyrics.embed(t["path"], entry)
        if problem:
            return jsonify(dict(entry, source="online", error=problem))
        index.refresh(tid)
    return jsonify(dict(entry, source="online"))


@app.route("/api/lyrics-sync", methods=["GET", "POST"])
def api_lyrics_sync():
    """Lyrics synchronization of the whole library (Settings). POST {"stop": true} stops it."""
    import lyrics_sync
    if request.method == "POST":
        if (request.get_json(silent=True) or {}).get("stop"):
            lyrics_sync.stop()
        else:
            lyrics_sync.start(index.all(), _save_synced, _save_lyrics_cache)
    return jsonify(lyrics_sync.STATUS)


def _lyrics_for_new(t: dict) -> None:
    """A downloaded song gets its synced lyrics (and the plain ones) right away when they are found and checked."""
    if not queue.conf().get("lyrics", True):
        return

    def run():
        import lyrics_sync
        try:
            got = lyrics_sync.find(t.get("title") or "", (t.get("artists") or [t.get("artist") or ""])[0], t.get("length_s") or 0)
            if got:
                _save_synced(t, got)
                _save_lyrics_cache()
        except Exception as exc:
            print(f"Synced lyrics for a new song failed: {exc}", flush=True)
    threading.Thread(target=run, daemon=True).start()


queue.lib.on_add = _lyrics_for_new

lyrics_suggest = JsonStore(DATA_DIR / "lyrics-suggest.json", {"found": {}, "checked": {}, "dismissed": []})


@app.route("/api/lyrics-suggest", methods=["GET", "POST"])
def api_lyrics_suggest():
    """Synced lyrics found by the app for songs without them. GET also starts a look (at most every 6 h; after a
    download, at once). POST {"apply": [ids] | "all"} writes them (synced and plain); {"dismiss": [ids]} forgets them."""
    import lyrics_sync
    data = request.get_json(silent=True) or {}
    store = lyrics_suggest.data
    found = store.setdefault("found", {})
    if request.method == "POST":
        ids = list(found) if data.get("apply") == "all" else [str(i) for i in data.get("apply") or []]
        done = 0
        for tid in ids:
            entry, t = found.get(tid), index.get(tid)
            if entry and t:
                _save_synced(t, {"synced": entry["synced"], "plain": entry.get("plain") or ""})
                done += 1
            found.pop(tid, None)
        for tid in data.get("dismiss") or []:
            if found.pop(str(tid), None) is not None:
                store.setdefault("dismissed", []).append(str(tid))
        with lyrics_suggest.lock:
            lyrics_suggest.save()
        _save_lyrics_cache()
        return jsonify({"applied": done, "count": len(found)})
    if not index.status.get("running"):
        lyrics_sync.suggest_scan(index.all(), lyrics_suggest, lyrics_cache.data, force=bool(request.args.get("now")))
    items = [{"id": tid, "title": e.get("title", ""), "artist": e.get("artist", ""),
              "preview": [l.split("]", 1)[-1].strip() for l in e.get("synced", "").splitlines() if l.split("]", 1)[-1].strip()][:4]}
             for tid, e in found.items() if index.get(tid)]
    return jsonify({**lyrics_sync.SUGGEST, "count": len(items), "items": items})


# Online pages of an artist or album (shown when MusicFlow is installed): their songs on Deezer, to download.
@app.route("/api/online/artist")
def api_online_artist():
    import artists
    import lyrics as ly
    name, aid = request.args.get("name", ""), request.args.get("id", "")
    try:
        if aid.isdigit():  # an artist picked in the online search
            return jsonify(artists.discography(int(aid)))
        found = artists.search_artists(name, 5)
        if not found:
            return jsonify({"error": "This artist was not found online."}), 404
        a = next((x for x in found if ly._key(x["name"]) == ly._key(name)), found[0])
        return jsonify(artists.discography(a["id"]))
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502


@app.route("/api/online/artists")
def api_online_artists():
    """Artists found online for a search (Search › Online › Artists): name, photo, fans, number of albums."""
    import artists
    q = request.args.get("q", "").strip()
    if not q:
        return jsonify({"artists": []})
    try:
        return jsonify({"artists": artists.search_artists(q, 12)})
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502


@app.route("/api/online/album")
def api_online_album():
    """?id=<deezer album id>, or ?artist=&album= to find it."""
    import urllib.parse
    import artists
    import lyrics as ly
    try:
        aid = request.args.get("id", "")
        if not aid:
            art, alb = request.args.get("artist", ""), request.args.get("album", "")
            q = f'artist:"{ly._artist(art)}" album:"{alb}"'
            res = (artists._get("/search/album?" + urllib.parse.urlencode({"q": q, "limit": 10})) or {}).get("data") or []
            res = [r for r in res if ly._key(r.get("title")) == ly._key(alb)] or res
            if not res:
                return jsonify({"error": "This album was not found online."}), 404
            aid = res[0]["id"]
        return jsonify({"id": aid, "tracks": artists.album_titles(int(aid))})
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502


@app.route("/api/album-fix", methods=["GET", "POST"])
def api_album_fix():
    """Albums fix of the whole library (Settings). POST {"stop": true} stops it."""
    import album_fix
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        if data.get("stop"):
            album_fix.stop()
        else:
            album_fix.start(index.all(), _save_album, shorten_names=bool(data.get("shorten")), covers=bool(data.get("covers")))
    return jsonify(album_fix.STATUS)


def _save_album(t: dict, found: dict) -> str:
    fields = {_FIELD_NAMES.get(k, k): v for k, v in found.items() if v and not k.startswith("_")}
    return _write_tags(t, fields, found.get("_cover"))


# Editing a song's information (title, artist, album…), by hand or from what was found online.
_FIELD_NAMES = {"title": "song_title", "artist": "artist_name", "track_no": "track_info"}
_EDITABLE = ("title", "artist", "album", "album_artist", "year", "genre", "track_no")
_write_lock = threading.Lock()


def _write_tags(t: dict, fields: dict, cover=None) -> str:
    import tag_quality
    with _write_lock:
        problem = tag_quality.write_value(t["path"], fields) if fields else ""
        if not problem and cover:
            problem = tag_quality.cover_art(t["path"], cover[0], cover[1])
        if fields or cover:
            index.refresh(t["id"])
    return problem


@app.route("/api/tags", methods=["POST"])
def api_tags():
    """{"ids": [...], "fields": {"title", "artist", "album", "album_artist", "year", "genre", "track_no"},
    "cover_url": "https://…dzcdn.net/…"}: only the fields given are written, in every song given."""
    import album_fix
    data = request.get_json(silent=True) or {}
    ids = [str(i) for i in data.get("ids") or []][:2000]
    fields = {_FIELD_NAMES.get(k, k): str(v or "").strip()[:500] for k, v in (data.get("fields") or {}).items() if k in _EDITABLE}
    if "song_title" in fields and not fields["song_title"]:
        return jsonify({"error": "The title can't be empty."}), 400
    cover = None
    if data.get("cover_url"):
        try:
            cover = album_fix.cover(str(data["cover_url"]))
        except Exception as exc:
            return jsonify({"error": f"The cover could not be downloaded: {exc}"}), 502
    done, tracks = 0, []
    for tid in ids:
        t = index.get(tid)
        if not t:
            continue
        problem = _write_tags(t, fields, cover)
        if problem:
            denied = "Errno 13" in problem or "ermission" in problem or "EACCES" in problem
            return jsonify({"error": "The app is not allowed to change this file." if denied else f"Could not change “{t['title']}”: {problem}",
                            "needs_access": denied, "done": done}), 403 if denied else 500
        done += 1
        tracks.append(index.get(tid))
    if not done:
        return jsonify({"error": "Track not found."}), 404
    return jsonify({"done": done, "tracks": tracks})


@app.route("/api/tracks/<tid>/details")
def api_track_details(tid):
    """Everything about a song's file: path, size, format, quality, tags, cover and lyrics."""
    import mutagen
    import tag_quality
    t, err = _track_or_404(tid)
    if err:
        return err
    f = Path(t["path"])
    st = f.stat()
    out = {"path": str(f), "folder": str(f.parent), "file": f.name, "size_bytes": st.st_size, "mtime": int(st.st_mtime),
           "format": f.suffix.lower().lstrip("."), "tags": {k: v for k, v in tag_quality.read_value(f).items() if k not in ("cover_art", "lyrics_data", "length_s")}}
    try:
        m = mutagen.File(str(f))
        info = m.info if m else None
        out.update(length_s=round(getattr(info, "length", 0) or 0, 1), bitrate=int(getattr(info, "bitrate", 0) or 0),
                   sample_rate=int(getattr(info, "sample_rate", 0) or 0), channels=int(getattr(info, "channels", 0) or 0),
                   bits=int(getattr(info, "bits_per_sample", 0) or 0), codec=getattr(info, "codec_description", "") or type(info).__module__.rsplit(".", 1)[-1] if info else "")
    except Exception:
        pass
    c = cover_bytes(f)
    out["cover"] = {"size_bytes": len(c[0]), "mime": c[1]} if c else None
    ly = file_lyrics(f)
    out["lyrics"] = "synced (.lrc)" if f.with_suffix(".lrc").is_file() else "synced" if ly["synced"] else "plain" if ly["plain"] else ""
    return jsonify(out)


@app.route("/api/tracks/<tid>/online")
def api_track_online(tid):
    """The song found online (Deezer), best first, to fill in its information."""
    import urllib.parse
    import album_fix
    import artists
    t, err = _track_or_404(tid)
    if err:
        return err
    title, artist = request.args.get("title") or t["title"], request.args.get("artist") or t.get("artist") or ""
    try:
        res = []
        for q in (f'artist:"{lyrics._artist(artist)}" track:"{lyrics._titles(title)[-1]}"' if artist else "", f"{lyrics._artist(artist)} {title}".strip()):
            if q and len(res) < 3:
                res += [r for r in (artists._get("/search?" + urllib.parse.urlencode({"q": q, "limit": 15})) or {}).get("data") or []
                        if r.get("id") not in {x.get("id") for x in res}]
        length = float(t.get("length_s") or 0)

        def score(r):
            ra = lyrics._key((r.get("artist") or {}).get("name"))
            a = lyrics._key(lyrics._artist(artist))
            d = abs(float(r.get("duration") or 0) - length) if length else 0
            return album_fix._sim(title, r.get("title") or "") * 2 + (1 if a and ra and (a in ra or ra in a) else 0) - min(d, 30) / 15
        res.sort(key=score, reverse=True)
        from concurrent.futures import ThreadPoolExecutor
        top = res[:6]
        with ThreadPoolExecutor(6) as pool:
            full = list(pool.map(lambda r: artists._safe(artists._get, f"/track/{r['id']}") or {}, top))
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502
    out = []
    for r, x in zip(top, full):
        al = x.get("album") or r.get("album") or {}
        out.append({"title": r.get("title") or "", "artist": ", ".join(c.get("name") for c in x.get("contributors") or [] if c.get("name")) or (r.get("artist") or {}).get("name") or "",
                    "album": al.get("title") or "", "album_artist": (x.get("artist") or r.get("artist") or {}).get("name") or "",
                    "year": (x.get("release_date") or al.get("release_date") or "")[:4], "track_no": str(x.get("track_position") or ""),
                    "cover": al.get("cover_xl") or al.get("cover_big") or "", "thumb": al.get("cover_medium") or "",
                    "length_s": r.get("duration") or 0, "sure": score(r) >= 2.8})
    if not out:
        return jsonify({"error": "This song was not found online."}), 404
    return jsonify({"results": out})


@app.route("/api/online/album-match", methods=["POST"])
def api_album_match():
    """{"ids": [...], "album": optional name to search}: the albums online holding these songs, with the track
    number of each song on it."""
    import album_fix
    data = request.get_json(silent=True) or {}
    local = [t for t in (index.get(str(i)) for i in (data.get("ids") or [])[:300]) if t]
    if not local:
        return jsonify({"error": "Track not found."}), 404
    try:
        found = album_fix.match_album(local, str(data.get("album") or ""), str(data.get("artist") or ""))
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502
    if not found:
        return jsonify({"error": "This album was not found online."}), 404
    for m in found:
        m["sure"] = album_fix.sure(local, m)
        m["short"] = album_fix.shorten(m["album"])
    return jsonify({"results": found[:4], "count": len(local)})


def _save_synced(t: dict, found: dict) -> str:
    """Replaces a song's lyrics: the .lrc next to it and the lyrics in the file. Where the folder can't be
    written (Android without access to all files), the lyrics are kept by the player instead."""
    with lyrics_cache.lock:
        lyrics_cache.data[t["id"]] = dict(found, verified=True)
        if len(lyrics_cache.data) % 25 == 0:
            lyrics_cache.save()
    lyrics.embed(t["path"], found)  # also writes the .lrc; may fail on a read-only folder
    with _sync_lock:
        index.refresh(t["id"])
    return ""


_sync_lock = threading.Lock()


def _save_lyrics_cache() -> None:
    with lyrics_cache.lock:
        lyrics_cache.save()


@app.route("/api/collections")
def api_collections():
    return jsonify(collections.snapshot())


@app.route("/api/favorites", methods=["POST"])
def api_favorites():
    data = request.get_json(silent=True) or {}
    tid = str(data.get("id") or "")
    if not tid:
        return jsonify({"error": "Missing track."}), 400
    return jsonify({"favorites": collections.set_favorite(tid, bool(data.get("on", True)))})


def _backup_dir() -> Path:
    root = (settings.data.get("dl") or {}).get("root") or next(iter(settings.data["folders"]), "") or str(DATA_DIR)
    return Path(root) / backup.BACKUP_DIR_NAME


@app.route("/api/backup", methods=["GET", "POST"])
def api_backup():
    folder = _backup_dir()
    if request.method == "GET":
        return jsonify({"dir": str(folder), "files": backup.list_files(folder)})
    data = request.get_json(silent=True) or {}
    tracks = index.all()
    if data.get("save"):
        b = backup.make(collections.snapshot(), plays.snapshot(since=0), tracks, data.get("prefs") or {}, VERSION)
        try:
            f = backup.save_file(folder, b)
        except OSError as exc:
            return jsonify({"error": f"The backup could not be saved: {exc.strerror or exc}"}), 400
        return jsonify({"file": str(f), "dir": str(folder), "files": backup.list_files(folder)})
    src = data.get("data")
    if data.get("name"):
        name = Path(str(data["name"])).name
        try:
            src = json.loads((folder / name).read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            return jsonify({"error": f"This backup can't be read: {exc}"}), 400
    try:
        return jsonify(backup.restore(src, collections, plays, tracks))
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400


@app.route("/api/changelog")
def api_changelog():
    for f in (paths.WEB_DIR / "CHANGELOG.md", paths.ROOT / "CHANGELOG.md"):
        if f.is_file():
            text = f.read_text(encoding="utf-8")
            break
    else:
        return jsonify({"versions": []})
    return jsonify({"versions": backup.changelog(text, request.args.get("since", ""), VERSION)})


@app.route("/api/storage")
def api_storage():
    out = []
    for folder in settings.data["folders"]:
        try:
            u = shutil.disk_usage(folder)
            out.append({"folder": folder, "free": u.free, "total": u.total})
        except OSError:
            pass
    return jsonify({"disks": out})


@app.route("/api/follow", methods=["POST"])
def api_follow():
    data = request.get_json(silent=True) or {}
    name, aid = str(data.get("name") or "").strip(), data.get("id")
    on = bool(data.get("on", True))
    if not str(aid or "").isdigit():
        if not on:
            follows = [a for a in collections.snapshot().get("follows") or [] if discovery._norm(a["name"]) == discovery._norm(name)]
            aid = follows[0]["id"] if follows else None
        else:
            import artists
            try:
                found = artists.search_artists(name, 5)
            except Exception as exc:
                return jsonify({"error": f"Online search failed: {exc}"}), 502
            if not found:
                return jsonify({"error": "This artist was not found online."}), 404
            best = next((x for x in found if discovery._norm(x["name"]) == discovery._norm(name)), found[0])
            aid, name = best["id"], best["name"]
    if aid is None:
        return jsonify({"follows": collections.snapshot().get("follows") or []})
    follows = collections.set_follow(int(aid), name, on)
    if on:
        releases.check(follows, force=True)
    return jsonify({"follows": follows})


@app.route("/api/releases", methods=["GET", "POST"])
def api_releases():
    data = request.get_json(silent=True) or {}
    if data.get("seen"):
        releases.seen()
    follows = collections.snapshot().get("follows") or []
    return jsonify(releases.check(follows, force=bool(data.get("check"))))


@app.route("/api/discover")
def api_discover():
    names = [n for n in request.args.get("artists", "").split("|") if n.strip()][:5]
    if not names:
        return jsonify({"songs": []})
    have = {discovery.song_key(t.get("title"), t.get("artist")) for t in index.all()}
    import time as _t
    try:
        return jsonify(discovery.daily(disc_store, names, have, _t.strftime("%Y-%m-%d")))
    except Exception as exc:
        return jsonify({"error": f"Online search failed: {exc}"}), 502


@app.route("/api/ratings", methods=["POST"])
def api_ratings():
    data = request.get_json(silent=True) or {}
    tid = str(data.get("id") or "")
    if not tid:
        return jsonify({"error": "Missing track."}), 400
    return jsonify({"ratings": collections.set_rating(tid, data.get("stars"))})


@app.route("/api/plays", methods=["GET", "POST"])
def api_plays():
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        tid = str(data.get("id") or "")
        if not tid or len(tid) > 64:
            return jsonify({"error": "Missing track."}), 400
        return jsonify({"count": plays.add(tid, data.get("secs"))})
    since = request.args.get("since")
    return jsonify(plays.snapshot(int(since) if since and since.isdigit() else None))


@app.route("/api/playlists", methods=["POST"])
def api_playlist_create():
    data = request.get_json(silent=True) or {}
    return jsonify({"playlist": collections.create(data.get("name") or "", data.get("tracks"), data.get("rules"))})


@app.route("/api/playlists/<pid>", methods=["POST", "DELETE"])
def api_playlist(pid):
    if request.method == "DELETE":
        if not collections.delete(pid):
            return jsonify({"error": "Playlist not found."}), 404
        return jsonify({"ok": True})
    data = request.get_json(silent=True) or {}
    p = collections.update(pid, data.get("name"), data.get("tracks"), data.get("add"), data.get("rules"))
    if not p:
        return jsonify({"error": "Playlist not found."}), 404
    return jsonify({"playlist": p})


def _lan_address() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
    except Exception:
        return socket.gethostbyname(socket.gethostname())


def start_musicflow(dest: str = "", config_path: str = "") -> None:
    """Loads MusicFlow's code (search and download) in the background, then starts the download queue: what was
    not finished last time goes on."""
    mf_bridge.start(dest, config_path)

    def later():
        if mf_bridge.wait(120):
            d = mf_bridge.default_dest()
            if d and not (settings.data.get("dl") or {}).get("root"):
                use_folder(d)
        queue.start()
    threading.Thread(target=later, daemon=True).start()


def use_folder(dest) -> None:
    """The default download folder is read by the library too."""
    dest = Path(dest)
    if dest.is_dir() and not any(_inside(dest, Path(f)) for f in settings.data["folders"]):
        settings.data["folders"] = settings.data["folders"] + [str(dest)]
        settings.save()


def _inside(path: Path, folder: Path) -> bool:
    try:
        path.resolve().relative_to(folder.resolve())
        return True
    except (ValueError, OSError):
        return False


def main(argv=None):
    parser = argparse.ArgumentParser(description="MusicFlow Play")
    parser.add_argument("--lan", action="store_true", help="let phones on the same Wi-Fi open the player")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--no-musicflow", action="store_true", help="don't start MusicFlow (search and download)")
    args = parser.parse_args(argv)
    url = f"http://127.0.0.1:{PORT}"
    with socket.socket() as s:
        if s.connect_ex(("127.0.0.1", PORT)) == 0:
            if not args.no_browser:
                import webbrowser
                webbrowser.open(url)
            return
    host = "127.0.0.1"
    if args.lan:
        CONFIG["lan"] = True
        if not settings.data.get("lan_token"):
            settings.data["lan_token"] = secrets.token_urlsafe(24)
            settings.save()
        CONFIG["token"] = settings.data["lan_token"]
        host = "0.0.0.0"
    if not args.no_musicflow:
        start_musicflow()
    _scan_async()
    print(f"MusicFlow Play {VERSION} on {url}")
    if args.lan:
        print(f"On your phone (same Wi-Fi): http://{_lan_address()}:{PORT}/?t={CONFIG['token']}")
    if not args.no_browser:
        import webbrowser
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    from werkzeug.serving import make_server
    server = make_server(host, PORT, app, threaded=True)
    server.socket.set_inheritable(False)
    server.serve_forever()


if __name__ == "__main__":
    main()
