"""MusicFlow's code (online search, links, best video, download with tags, cover and lyrics) inside the player.

MusicFlow is part of the app: nothing to install, no second page and no second server. Its Flask app is imported
in this process and called directly (Flask's test client, which does not open any port), so the player's own page
and its download queue use MusicFlow's whole download chain without ever showing MusicFlow's interface."""
from __future__ import annotations

import json
import os
import sys
import threading
import time
from pathlib import Path

from paths import ROOT, data_dir

STATE = {"state": "off", "error": ""}  # off, starting, ready, error
_MF = {"module": None, "dest": ""}
_LOCK = threading.Lock()
_ALLOWED = ("search", "resolve-link", "resolve-track", "candidates", "artist/search", "artist/albums", "discover/",
            "artist/", "track-covers", "covers-by-name", "connection")


def _android() -> bool:
    return hasattr(sys, "getandroidapilevel")


def ssl_context():
    import ssl
    try:
        import truststore
        return truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    except Exception:
        pass
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def _paths() -> None:
    for d in (ROOT / "musicflow" / "desktop", ROOT / "musicflow" / "core"):
        if d.is_dir() and str(d) not in sys.path:
            sys.path.insert(0, str(d))


def start(dest: str = "", config_path: str = "") -> None:
    """Loads MusicFlow in the background (it takes a few seconds on a phone); the page waits for `ready`."""
    with _LOCK:
        if STATE["state"] in ("starting", "ready"):
            return
        STATE.update(state="starting", error="")
    threading.Thread(target=_load, args=(dest, config_path), daemon=True).start()


def _load(dest: str, config_path: str) -> None:
    try:
        if _android():
            import mobile_app as mf  # MusicFlow's Android server code (packed in the app)
            config = config_path or str(data_dir() / "musicflow-config.json")
            mf.configure(dest or str(data_dir() / "Music"), config)
        else:
            _paths()
            import app as mf  # MusicFlow's PC server code (desktop/app.py)
        _MF["module"] = mf
        _MF["dest"] = str(getattr(mf, "DEFAULT_DEST", "") or dest or "")
        prewarm = getattr(mf, "_prewarm_search", None)
        if prewarm:
            threading.Thread(target=prewarm, daemon=True).start()
        STATE.update(state="ready", error="")
    except Exception as exc:  # missing packages (source without MusicFlow's requirements)
        STATE.update(state="error", error=f"{type(exc).__name__}: {exc}")
        print(f"MusicFlow (search and download) is not available: {exc}", file=sys.stderr)


def ready() -> bool:
    return STATE["state"] == "ready"


def wait(seconds: float = 60) -> bool:
    import time
    end = time.time() + seconds
    while STATE["state"] == "starting" and time.time() < end:
        time.sleep(0.2)
    return ready()


def default_dest() -> str:
    return _MF["dest"]


def call(path: str, payload=None, method: str = "POST") -> tuple[int, dict]:
    """Calls one of MusicFlow's routes (e.g. "search", "download", "status/<id>") in this process."""
    if not wait():
        return 503, {"error": "Search and download are not available: " + (STATE["error"] or "still starting")}
    mf = _MF["module"]
    client = mf.app.test_client()
    headers = {"X-MF-Token": getattr(mf, "API_TOKEN", "")}
    url = "/api/" + path.lstrip("/")
    try:
        if method == "GET":
            r = client.get(url, headers=headers, base_url="http://127.0.0.1:5090")
        else:
            r = client.post(url, json=payload or {}, headers=headers, base_url="http://127.0.0.1:5090")
        return r.status_code, (r.get_json(silent=True) or {})
    except Exception as exc:
        return 500, {"error": f"{type(exc).__name__}: {exc}"}


_SEARCH = {"lock": threading.Lock(), "gen": 0, "cache": {}}


def search(payload: dict) -> tuple[int, dict]:
    """Online search, one at a time and only the latest: while the person types, each pause asked for a search and
    they all ran one after the other (MusicFlow's search opens a web page and runs yt-dlp), so the app stalled for
    seconds. Now a search that waited while a newer one was asked returns at once, and answers are kept 10 min."""
    key = json.dumps({k: payload.get(k) for k in ("title", "limit", "source")}, sort_keys=True).lower()
    hit = _SEARCH["cache"].get(key)
    if hit and time.time() - hit[0] < 600:
        return 200, hit[1]
    with _LOCK:
        _SEARCH["gen"] += 1
        mine = _SEARCH["gen"]
    with _SEARCH["lock"]:
        if mine != _SEARCH["gen"]:
            return 409, {"superseded": True, "error": "A newer search was asked."}
        hit = _SEARCH["cache"].get(key)
        if hit and time.time() - hit[0] < 600:
            return 200, hit[1]
        code, data = call("search", payload)
    if code == 200 and data.get("results") is not None:
        cache = _SEARCH["cache"]
        cache[key] = (time.time(), data)
        for k in sorted(cache, key=lambda k: cache[k][0])[:-50]:
            cache.pop(k, None)
    return code, data


def allowed(path: str) -> bool:
    """Routes the player's page may call directly (searches and online pages; downloads go through the queue)."""
    return any(path == a or (a.endswith("/") and path.startswith(a)) for a in _ALLOWED) and ".." not in path


def env_dest_default() -> str:
    return os.environ.get("MFP_DOWNLOAD_DIR", "")
