from __future__ import annotations

import json
import threading
import time
import uuid
from pathlib import Path


class JsonStore:
    def __init__(self, file_path: Path, default: dict):
        self.file_path = Path(file_path)
        self.lock = threading.Lock()
        self.data = json.loads(json.dumps(default))
        try:
            loaded = json.loads(self.file_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                self.data.update(loaded)
        except Exception:
            pass

    def save(self) -> None:
        tmp = self.file_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self.file_path)


def _ids(values) -> list[str]:
    out = []
    for v in values or []:
        if isinstance(v, str) and v and len(v) <= 64 and v not in out:
            out.append(v)
    return out


RULE_FIELDS = {"title", "artist", "album", "genre", "year", "length", "rating", "plays", "played", "added", "favorite", "folder", "format"}
RULE_OPS = {"is", "not", "has", "lacks", "gt", "lt", "within", "before"}


def _rules(rules) -> dict:
    """A smart playlist: songs matching all (or any) of the rules, sorted, at most `limit` of them."""
    rules = rules if isinstance(rules, dict) else {}
    out = []
    for r in (rules.get("rules") or [])[:12]:
        if isinstance(r, dict) and r.get("f") in RULE_FIELDS and r.get("op") in RULE_OPS:
            out.append({"f": r["f"], "op": r["op"], "v": str(r.get("v", ""))[:120]})
    try:
        limit = max(0, min(5000, int(rules.get("limit") or 0)))
    except (TypeError, ValueError):
        limit = 0
    return {"match": "any" if rules.get("match") == "any" else "all", "rules": out,
            "sort": str(rules.get("sort") or "")[:20], "limit": limit}


class Plays(JsonStore):
    """What was listened to: per song the number of plays, the last time and the time listened, and a log of the
    plays (for the statistics of the week, the month, the year)."""
    LOG_MAX = 20000

    def __init__(self, file_path: Path):
        super().__init__(file_path, {"counts": {}, "log": []})

    def add(self, tid: str, secs, at=None) -> list:
        try:
            secs = max(0, min(36000, int(secs or 0)))
        except (TypeError, ValueError):
            secs = 0
        at = int(at or time.time())
        with self.lock:
            c = self.data["counts"].get(tid) or [0, 0, 0]
            c = [c[0] + 1, at, c[2] + secs]
            self.data["counts"][tid] = c
            log = self.data["log"]
            log.append([at, tid, secs])
            if len(log) > self.LOG_MAX:
                del log[:len(log) - self.LOG_MAX]
            self.save()
            return c

    def snapshot(self, since=None) -> dict:
        with self.lock:
            out = {"counts": dict(self.data["counts"])}
            if since is not None:
                out["log"] = [e for e in self.data["log"] if e[0] >= since]
            return out

    def save(self) -> None:
        tmp = self.file_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        tmp.replace(self.file_path)


class Collections(JsonStore):
    def __init__(self, file_path: Path):
        super().__init__(file_path, {"favorites": [], "playlists": [], "ratings": {}})

    def snapshot(self) -> dict:
        with self.lock:
            return json.loads(json.dumps(self.data))

    def set_favorite(self, tid: str, on: bool) -> list[str]:
        with self.lock:
            favs = [x for x in self.data["favorites"] if x != tid]
            if on:
                favs.insert(0, tid)
            self.data["favorites"] = favs
            self.save()
            return list(favs)

    def _find(self, pid: str) -> dict | None:
        return next((p for p in self.data["playlists"] if p["id"] == pid), None)

    def set_rating(self, tid: str, stars) -> dict:
        """1 to 5 stars; 0 takes the rating away."""
        try:
            stars = max(0, min(5, int(stars)))
        except (TypeError, ValueError):
            stars = 0
        with self.lock:
            r = self.data.setdefault("ratings", {})
            if stars:
                r[tid] = stars
            else:
                r.pop(tid, None)
            self.save()
            return dict(r)

    def create(self, name: str, tracks=None, rules=None) -> dict:
        p = {"id": uuid.uuid4().hex[:12], "name": (name or "").strip()[:120] or "New playlist",
             "tracks": _ids(tracks), "created": int(time.time()), "updated": int(time.time())}
        if rules is not None:
            p["rules"] = _rules(rules)
        with self.lock:
            self.data["playlists"].append(p)
            self.save()
        return dict(p)

    def update(self, pid: str, name=None, tracks=None, add=None, rules=None) -> dict | None:
        with self.lock:
            p = self._find(pid)
            if not p:
                return None
            if name is not None and str(name).strip():
                p["name"] = str(name).strip()[:120]
            if tracks is not None:
                p["tracks"] = _ids(tracks)
            if rules is not None:
                p["rules"] = _rules(rules)
            if add:
                p["tracks"] = p["tracks"] + [t for t in _ids(add) if t not in p["tracks"]]
            p["updated"] = int(time.time())
            self.save()
            return dict(p)

    def delete(self, pid: str) -> bool:
        with self.lock:
            before = len(self.data["playlists"])
            self.data["playlists"] = [p for p in self.data["playlists"] if p["id"] != pid]
            if len(self.data["playlists"]) == before:
                return False
            self.save()
            return True
