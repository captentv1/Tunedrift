"""Backup and restore of what the person made in the player (favorites, playlists, ratings, listening history and
the app's settings), and the changelog shown after an update."""
from __future__ import annotations

import json
import re
import time
import unicodedata
from pathlib import Path

BACKUP_DIR_NAME = "MusicFlow Play backups"


def _norm(s) -> str:
    s = unicodedata.normalize("NFKD", str(s or "")).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def make(collections: dict, plays: dict, tracks: list[dict], prefs: dict, version: str) -> dict:
    """Everything is saved with the songs it points to (path, title, artist), so it can be found again on another
    phone or after the folders moved."""
    used = set(collections.get("favorites") or [])
    for p in collections.get("playlists") or []:
        used.update(p.get("tracks") or [])
    used.update((collections.get("ratings") or {}).keys())
    used.update((plays.get("counts") or {}).keys())
    songs = {t["id"]: [t.get("path", ""), t.get("title", ""), t.get("artist", "")] for t in tracks if t["id"] in used}
    return {"app": "MusicFlow Play", "kind": "backup", "version": version, "created": int(time.time()),
            "collections": collections, "plays": plays, "prefs": prefs if isinstance(prefs, dict) else {}, "songs": songs}


def remap(backup: dict, tracks: list[dict]) -> dict:
    """old id -> id of the same song here (same id, same path, or same title and artist)."""
    here = {t["id"] for t in tracks}
    by_path = {str(t.get("path", "")): t["id"] for t in tracks}
    by_name = {}
    for t in tracks:
        by_name.setdefault(_norm(t.get("title")) + "|" + _norm(t.get("artist")), t["id"])
    out = {}
    for old, info in (backup.get("songs") or {}).items():
        if old in here:
            out[old] = old
            continue
        path, title, artist = (list(info) + ["", "", ""])[:3]
        new = by_path.get(str(path)) or by_name.get(_norm(title) + "|" + _norm(artist))
        if new:
            out[old] = new
    return out


def restore(backup: dict, coll, plays, tracks: list[dict]) -> dict:
    """Adds the backup to what is here: nothing here is lost. Returns the counts and the app settings."""
    if not isinstance(backup, dict) or backup.get("kind") != "backup":
        raise ValueError("This file is not a MusicFlow Play backup.")
    ids = remap(backup, tracks)
    m = lambda xs: [ids[x] for x in xs or [] if x in ids]
    c = backup.get("collections") or {}
    n = {"favorites": 0, "playlists": 0, "ratings": 0, "plays": 0, "missing": len(backup.get("songs") or {}) - len(ids)}
    with coll.lock:
        favs = coll.data["favorites"]
        for x in m(c.get("favorites")):
            if x not in favs:
                favs.append(x)
                n["favorites"] += 1
        have = {p["id"]: p for p in coll.data["playlists"]}
        for p in c.get("playlists") or []:
            if not isinstance(p, dict) or not p.get("id"):
                continue
            q = dict(p, tracks=m(p.get("tracks")))
            if p["id"] in have:
                have[p["id"]].update(q)
            else:
                coll.data["playlists"].append(q)
            n["playlists"] += 1
        r = coll.data.setdefault("ratings", {})
        for old, stars in (c.get("ratings") or {}).items():
            if old in ids and ids[old] not in r:
                r[ids[old]] = stars
                n["ratings"] += 1
        coll.save()
    p = backup.get("plays") or {}
    with plays.lock:
        counts = plays.data["counts"]
        for old, v in (p.get("counts") or {}).items():
            if old in ids:
                cur = counts.get(ids[old]) or [0, 0, 0]
                counts[ids[old]] = [max(cur[0], v[0]), max(cur[1], v[1]), max(cur[2], v[2])]
        seen = {(e[0], e[1]) for e in plays.data["log"]}
        for at, old, secs in p.get("log") or []:
            if old in ids and (at, ids[old]) not in seen:
                plays.data["log"].append([at, ids[old], secs])
                n["plays"] += 1
        plays.data["log"].sort(key=lambda e: e[0])
        del plays.data["log"][:max(0, len(plays.data["log"]) - plays.LOG_MAX)]
        plays.save()
    return {"restored": n, "prefs": backup.get("prefs") or {}}


def save_file(folder: Path, data: dict) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    f = folder / time.strftime("MusicFlow Play backup %Y-%m-%d %H-%M.json")
    f.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return f


def list_files(folder: Path) -> list[dict]:
    try:
        files = sorted(folder.glob("MusicFlow Play backup *.json"), key=lambda f: f.stat().st_mtime, reverse=True)
    except OSError:
        return []
    return [{"name": f.name, "size": f.stat().st_size, "at": int(f.stat().st_mtime)} for f in files[:20]]


# ---- what's new -------------------------------------------------------------------------------------------------

def _vkey(v: str):
    return tuple(int(x) for x in re.findall(r"\d+", v or "")[:4])


def changelog(text: str, since: str = "", upto: str = "", limit: int = 6) -> list[dict]:
    """The versions newer than `since` (and not newer than `upto`), newest first: [{v, title, body}]."""
    out = []
    for m in re.finditer(r"^## (\d+(?:\.\d+)+)([^\n]*)\n(.*?)(?=^## |\Z)", text, re.M | re.S):
        v = m.group(1)
        if since and _vkey(v) <= _vkey(since):
            continue
        if upto and _vkey(v) > _vkey(upto):
            continue
        out.append({"v": v, "title": m.group(2).strip(" —-"), "body": m.group(3).strip()})
        if len(out) >= limit:
            break
    return out
