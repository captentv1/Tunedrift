from __future__ import annotations

import argparse
import hmac
import io
import mimetypes
import os
import secrets
import socket
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import paths

paths.use_core()

from flask import Flask, jsonify, request, send_file

import lyrics
from collections_store import Collections, JsonStore
from library_index import LibraryIndex, cover_bytes, file_lyrics, plain_from_synced

PORT = 5091
RELEASES = "https://github.com/captentv1/musicflow-play/releases"
VERSION = "1.0.0.2"
DATA_DIR = paths.data_dir()
DEFAULT_FOLDERS = [f for f in os.environ.get("MFP_DEFAULT_FOLDERS", "").split(os.pathsep) if f] or [str(Path.home() / "Music")]
BROWSE_ROOTS = [f for f in os.environ.get("MFP_BROWSE_ROOTS", "").split(os.pathsep) if f]
_AUDIO_TYPES = {".mp3": "audio/mpeg", ".flac": "audio/flac", ".wav": "audio/wav", ".m4a": "audio/mp4",
                ".mp4": "audio/mp4", ".opus": "audio/ogg", ".ogg": "audio/ogg", ".webm": "audio/webm"}

app = Flask(__name__, static_folder=None)
settings = JsonStore(DATA_DIR / "settings.json", {"folders": list(DEFAULT_FOLDERS), "lan_token": ""})
index = LibraryIndex(DATA_DIR / "library-index.json")
collections = Collections(DATA_DIR / "collections.json")
lyrics_cache = JsonStore(DATA_DIR / "lyrics-online.json", {})

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
                    "musicflow": CONFIG.get("musicflow") if _is_local_request() else None,
                    "module": _module_state() if _is_local_request() else None})


def _module_state():
    import musicflow_embed
    return dict(musicflow_embed.MODULE)


@app.route("/api/musicflow/install", methods=["POST"])
def api_musicflow_install():
    """Installs the Download tab (MusicFlow) from this version's GitHub release."""
    import musicflow_embed
    if not _is_local_request() or musicflow_embed.MODULE["state"] in ("bundled", "installed"):
        return jsonify({"error": "Not available."}), 403
    url = musicflow_embed.module_url(RELEASES, VERSION)
    custom = str((request.get_json(silent=True) or {}).get("url") or "")
    if custom and CONFIG.get("module_url_any"):  # test builds only
        url = custom
    musicflow_embed.install(url)
    return jsonify(_module_state())


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
        import musicflow_embed  # its SSL context has the certificates (Android's Python has none of its own)
        urllib.request.build_opener(_NoRedirect, urllib.request.HTTPSHandler(context=musicflow_embed._ssl_context())).open(req, timeout=15).close()
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


@app.route("/api/album-fix", methods=["GET", "POST"])
def api_album_fix():
    """Albums fix of the whole library (Settings). POST {"stop": true} stops it."""
    import album_fix
    if request.method == "POST":
        if (request.get_json(silent=True) or {}).get("stop"):
            album_fix.stop()
        else:
            album_fix.start(index.all(), _save_album)
    return jsonify(album_fix.STATUS)


def _save_album(t: dict, found: dict) -> str:
    import tag_quality
    problem = tag_quality.write_value(t["path"], {k: v for k, v in found.items() if v})
    if not problem:
        index.refresh(t["id"])
    return problem


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


@app.route("/api/playlists", methods=["POST"])
def api_playlist_create():
    data = request.get_json(silent=True) or {}
    return jsonify({"playlist": collections.create(data.get("name") or "", data.get("tracks"))})


@app.route("/api/playlists/<pid>", methods=["POST", "DELETE"])
def api_playlist(pid):
    if request.method == "DELETE":
        if not collections.delete(pid):
            return jsonify({"error": "Playlist not found."}), 404
        return jsonify({"ok": True})
    data = request.get_json(silent=True) or {}
    p = collections.update(pid, data.get("name"), data.get("tracks"), data.get("add"))
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


def start_musicflow():
    """Starts MusicFlow next to the player; the songs it downloads go to a folder the player reads."""
    import musicflow_embed

    def run():
        mf = musicflow_embed.start()
        if mf:
            use_musicflow(getattr(mf, "DEFAULT_DEST", "") or "", musicflow_embed.URL)
    musicflow_embed.prepare(run, VERSION, RELEASES)


def use_musicflow(dest, url: str) -> None:
    """MusicFlow runs: show its Download tab and read the folder where it downloads."""
    CONFIG["musicflow"] = url
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
