"""Import: playlists from a link, a Spotify or YouTube account (their official APIs), another MusicFlow Play on the
same Wi-Fi (local server), or a playlist file / a list of titles. An imported playlist becomes a playlist of the
player, its songs go into the download queue (into the root folder chosen once), and the songs already on the
device are only linked, never downloaded again. A .m3u8 file is written next to the songs, so other players
(Samsung Music…) see the playlist too."""
from __future__ import annotations

import base64
import csv
import hashlib
import io
import json
import os
import re
import secrets
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
CTX: dict = {}  # set by init(): queue, collections, settings, data_dir, port, local check
_STATES: dict[str, tuple[float, str, str]] = {}  # OAuth state -> (time, provider, PKCE verifier)
_LOCK = threading.Lock()

SPOTIFY_AUTH = "https://accounts.spotify.com/authorize"
SPOTIFY_TOKEN = "https://accounts.spotify.com/api/token"
SPOTIFY_API = "https://api.spotify.com/v1"
SPOTIFY_SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
YOUTUBE_API = "https://www.googleapis.com/youtube/v3"
YOUTUBE_SCOPES = "https://www.googleapis.com/auth/youtube.readonly"


def init(queue, collections, settings, data_dir: Path, port: int):
    CTX.update(queue=queue, collections=collections, settings=settings, port=port,
               accounts=JsonStore(data_dir / "accounts.json", {"spotify": {}, "youtube": {}, "servers": []}),
               imports=JsonStore(data_dir / "imports.json", {"imports": []}))
    queue.on_song = on_song


def redirect_uri(provider: str) -> str:
    return f"http://127.0.0.1:{CTX['port']}/import/callback/{provider}"


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


def _pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)[:96]
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return verifier, challenge


# ---------------------------------------------------------------- accounts (official APIs, OAuth with PKCE)

def _acc(provider: str) -> dict:
    return CTX["accounts"].data.setdefault(provider, {})


def _save_acc():
    with CTX["accounts"].lock:
        CTX["accounts"].save()


def login_url(provider: str) -> str:
    a = _acc(provider)
    if not a.get("client_id"):
        raise ImportError_("Enter the Client ID first.")
    verifier, challenge = _pkce()
    state = secrets.token_urlsafe(24)
    with _LOCK:
        for k in [k for k, v in _STATES.items() if time.time() - v[0] > 900]:
            _STATES.pop(k, None)
        _STATES[state] = (time.time(), provider, verifier)
    if provider == "spotify":
        q = {"client_id": a["client_id"], "response_type": "code", "redirect_uri": redirect_uri("spotify"),
             "code_challenge_method": "S256", "code_challenge": challenge, "scope": SPOTIFY_SCOPES, "state": state}
        return SPOTIFY_AUTH + "?" + urllib.parse.urlencode(q)
    q = {"client_id": a["client_id"], "response_type": "code", "redirect_uri": redirect_uri("youtube"),
         "code_challenge_method": "S256", "code_challenge": challenge, "scope": YOUTUBE_SCOPES, "state": state,
         "access_type": "offline", "prompt": "consent"}
    return GOOGLE_AUTH + "?" + urllib.parse.urlencode(q)


def finish_login(provider: str, state: str, code: str) -> None:
    with _LOCK:
        found = _STATES.pop(state, None)
    if not found or found[1] != provider or time.time() - found[0] > 900:
        raise ImportError_("This sign-in link expired. Start again from the app.")
    a = _acc(provider)
    form = {"grant_type": "authorization_code", "code": code, "redirect_uri": redirect_uri(provider),
            "client_id": a.get("client_id", ""), "code_verifier": found[2]}
    if provider == "youtube" and a.get("client_secret"):
        form["client_secret"] = a["client_secret"]
    tok = _http("POST", SPOTIFY_TOKEN if provider == "spotify" else GOOGLE_TOKEN, form=form)
    _store_token(provider, tok)
    try:
        if provider == "spotify":
            me = _http("GET", SPOTIFY_API + "/me", {"Authorization": "Bearer " + a["access"]})
            a["user"] = me.get("display_name") or me.get("id") or ""
        else:
            me = _http("GET", YOUTUBE_API + "/channels?part=snippet&mine=true", {"Authorization": "Bearer " + a["access"]})
            a["user"] = ((me.get("items") or [{}])[0].get("snippet") or {}).get("title", "")
    except Exception:
        pass
    _save_acc()


def _store_token(provider: str, tok: dict):
    a = _acc(provider)
    a["access"] = tok.get("access_token", "")
    if tok.get("refresh_token"):
        a["refresh"] = tok["refresh_token"]
    a["expires"] = time.time() + int(tok.get("expires_in") or 3600) - 60
    _save_acc()


def _bearer(provider: str) -> dict:
    a = _acc(provider)
    if not a.get("access") and not a.get("refresh"):
        raise ImportError_("Not connected.")
    if time.time() > a.get("expires", 0) and a.get("refresh"):
        form = {"grant_type": "refresh_token", "refresh_token": a["refresh"], "client_id": a.get("client_id", "")}
        if provider == "youtube" and a.get("client_secret"):
            form["client_secret"] = a["client_secret"]
        _store_token(provider, _http("POST", SPOTIFY_TOKEN if provider == "spotify" else GOOGLE_TOKEN, form=form))
    return {"Authorization": "Bearer " + a["access"]}


def _paged(url: str, provider: str, key="items", limit_pages=60):
    out, pages = [], 0
    while url and pages < limit_pages:
        d = _http("GET", url, _bearer(provider))
        out += d.get(key) or []
        pages += 1
        if provider == "spotify":
            url = d.get("next")
        else:
            tok = d.get("nextPageToken")
            url = (re.sub(r"&pageToken=[^&]*", "", url) + "&pageToken=" + tok) if tok else None
    return out


def _sp_track(t: dict) -> dict | None:
    if not t or not t.get("name") or t.get("type", "track") != "track":
        return None
    images = (t.get("album") or {}).get("images") or []
    return {"title": t["name"], "artist": ", ".join(a.get("name", "") for a in t.get("artists") or []),
            "album": (t.get("album") or {}).get("name", ""), "duration": int((t.get("duration_ms") or 0) / 1000),
            "cover": images[0]["url"] if images else "", "url": ""}


def spotify_playlists() -> list[dict]:
    items = _paged(SPOTIFY_API + "/me/playlists?limit=50", "spotify")
    out = [{"id": "liked", "name": "Liked Songs", "count": None, "image": ""}]
    for p in items:
        if not p:
            continue
        total = (p.get("tracks") or p.get("items") or {}).get("total")
        out.append({"id": p["id"], "name": p.get("name") or "Playlist", "count": total,
                    "image": ((p.get("images") or [{}])[0] or {}).get("url", "")})
    return out


def spotify_tracks(pid: str) -> tuple[str, list[dict]]:
    if pid == "liked":
        rows = _paged(SPOTIFY_API + "/me/tracks?limit=50", "spotify")
        return "Liked Songs", [x for x in (_sp_track(r.get("track")) for r in rows) if x]
    pid = re.sub(r"[^A-Za-z0-9]", "", pid)
    info = _http("GET", f"{SPOTIFY_API}/playlists/{pid}?fields=name", _bearer("spotify"))
    try:
        rows = _paged(f"{SPOTIFY_API}/playlists/{pid}/tracks?limit=100", "spotify")
    except ImportError_ as exc:  # newer API name of the same list
        if exc.code not in (403, 404):
            raise
        rows = _paged(f"{SPOTIFY_API}/playlists/{pid}/items?limit=100", "spotify")
    return info.get("name") or "Playlist", [x for x in (_sp_track(r.get("track") or r.get("item")) for r in rows) if x]


_YT_NOISE = re.compile(r"\s*[\(\[](official|lyrics?|audio|video|clip|music video|hd|hq|4k|visualizer|paroles)[^\)\]]*[\)\]]", re.I)


def youtube_song(title_text: str, channel: str) -> tuple[str, str]:
    """"Artist - Title (Official Video)" -> ("Title", "Artist"); otherwise the channel is the artist."""
    t = _YT_NOISE.sub("", title_text or "").strip()
    if " - " in t:
        artist_name, song = [x.strip() for x in t.split(" - ", 1)]
        return song, artist_name
    channel = re.sub(r"\s*-\s*Topic$|VEVO$", "", channel or "").strip()
    return t, channel


def _iso_seconds(d: str) -> int:
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", d or "")
    return (int(m.group(1) or 0) * 3600 + int(m.group(2) or 0) * 60 + int(m.group(3) or 0)) if m else 0


def youtube_playlists() -> list[dict]:
    rows = _paged(YOUTUBE_API + "/playlists?part=snippet,contentDetails&mine=true&maxResults=50", "youtube")
    out = [{"id": "liked", "name": "Liked videos", "count": None, "image": ""}]
    for p in rows:
        sn = p.get("snippet") or {}
        out.append({"id": p["id"], "name": sn.get("title") or "Playlist", "count": (p.get("contentDetails") or {}).get("itemCount"),
                    "image": ((sn.get("thumbnails") or {}).get("medium") or {}).get("url", "")})
    return out


def youtube_tracks(pid: str) -> tuple[str, list[dict]]:
    out = []
    if pid == "liked":
        rows = _paged(YOUTUBE_API + "/videos?part=snippet,contentDetails&myRating=like&maxResults=50", "youtube", limit_pages=20)
        name = "Liked videos"
        for v in rows:
            sn = v.get("snippet") or {}
            song, art = youtube_song(sn.get("title"), sn.get("channelTitle"))
            out.append({"title": song, "artist": art, "duration": _iso_seconds((v.get("contentDetails") or {}).get("duration")),
                        "cover": ((sn.get("thumbnails") or {}).get("high") or {}).get("url", ""),
                        "url": f"https://www.youtube.com/watch?v={v['id']}"})
        return name, out
    pid = re.sub(r"[^A-Za-z0-9_-]", "", pid)
    info = _http("GET", f"{YOUTUBE_API}/playlists?part=snippet&id={pid}", _bearer("youtube"))
    name = (((info.get("items") or [{}])[0]).get("snippet") or {}).get("title") or "Playlist"
    for it in _paged(f"{YOUTUBE_API}/playlistItems?part=snippet&playlistId={pid}&maxResults=50", "youtube"):
        sn = it.get("snippet") or {}
        vid = (sn.get("resourceId") or {}).get("videoId")
        if not vid or sn.get("title") in ("Private video", "Deleted video"):
            continue
        song, art = youtube_song(sn.get("title"), sn.get("videoOwnerChannelTitle"))
        out.append({"title": song, "artist": art, "cover": ((sn.get("thumbnails") or {}).get("high") or {}).get("url", ""),
                    "url": f"https://www.youtube.com/watch?v={vid}"})
    return name, out


# ---------------------------------------------------------------- other MusicFlow Play (local server)

def parse_server(address: str) -> tuple[str, str]:
    """The link printed by `run_lan.bat` on the computer: http://192.168.1.20:5091/?t=…"""
    u = urllib.parse.urlsplit(address.strip() if "://" in address else "http://" + address.strip())
    if u.scheme not in ("http", "https") or not u.hostname:
        raise ImportError_("Paste the link shown by MusicFlow Play on the computer (http://…:5091/?t=…).")
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
    out = {"redirect": {p: redirect_uri(p) for p in ("spotify", "youtube")}, "servers": acc.get("servers", []),
           "imports": [{k: i.get(k) for k in ("id", "name", "source", "folder", "playlist", "count", "last")} | {"have": len(i.get("tracks") or {})}
                       for i in reversed(_imports())],
           "musicflow": mf_bridge.STATE, "default_root": mf_bridge.default_dest()}
    for p in ("spotify", "youtube"):
        a = acc.get(p) or {}
        out[p] = {"client_id": a.get("client_id", ""), "has_secret": bool(a.get("client_secret")),
                  "connected": bool(a.get("refresh") or a.get("access")), "user": a.get("user", "")}
    for i in out["imports"]:
        if (i.get("source") or {}).get("token"):
            i["source"] = {k: v for k, v in i["source"].items() if k != "token"}
    return jsonify(out)


@bp.route("/api/import/account", methods=["POST"])
def api_account():
    d = request.get_json(silent=True) or {}
    p = d.get("provider")
    if p not in ("spotify", "youtube"):
        return _err("Unknown service.", 404)
    a = _acc(p)
    if d.get("logout"):
        for k in ("access", "refresh", "expires", "user"):
            a.pop(k, None)
    else:
        cid = str(d.get("client_id") or "").strip()
        if not re.fullmatch(r"[\w.\-]{8,200}", cid):
            return _err("This Client ID does not look right.")
        a["client_id"] = cid
        if d.get("client_secret") is not None:
            a["client_secret"] = str(d.get("client_secret") or "").strip()
    _save_acc()
    return jsonify({"ok": True})


@bp.route("/api/import/login/<provider>")
def api_login(provider):
    if provider not in ("spotify", "youtube"):
        return _err("Unknown service.", 404)
    try:
        return jsonify({"url": login_url(provider)})
    except ImportError_ as exc:
        return _err(exc)


@bp.route("/import/callback/<provider>")
def callback(provider):
    """The browser comes back here after signing in (no token: the one-time state is the key)."""
    page = ("<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px sans-serif;"
            "background:#111;color:#eee;padding:24px'><h2>{}</h2><p>{}</p>")
    if request.args.get("error"):
        return page.format("Sign-in cancelled", "Go back to MusicFlow Play."), 400
    try:
        finish_login(provider, request.args.get("state", ""), request.args.get("code", ""))
    except Exception as exc:
        return page.format("Sign-in failed", str(exc).replace("<", "&lt;")), 400
    return page.format("Connected", "You can close this page and go back to MusicFlow Play.")


@bp.route("/api/import/playlists", methods=["POST"])
def api_playlists():
    d = request.get_json(silent=True) or {}
    try:
        kind = d.get("kind")
        if kind == "spotify":
            return jsonify({"playlists": spotify_playlists()})
        if kind == "youtube":
            return jsonify({"playlists": youtube_playlists()})
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
