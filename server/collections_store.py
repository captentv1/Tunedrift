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


class Collections(JsonStore):
    def __init__(self, file_path: Path):
        super().__init__(file_path, {"favorites": [], "playlists": []})

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

    def create(self, name: str, tracks=None) -> dict:
        p = {"id": uuid.uuid4().hex[:12], "name": (name or "").strip()[:120] or "New playlist",
             "tracks": _ids(tracks), "created": int(time.time()), "updated": int(time.time())}
        with self.lock:
            self.data["playlists"].append(p)
            self.save()
        return dict(p)

    def update(self, pid: str, name=None, tracks=None, add=None) -> dict | None:
        with self.lock:
            p = self._find(pid)
            if not p:
                return None
            if name is not None and str(name).strip():
                p["name"] = str(name).strip()[:120]
            if tracks is not None:
                p["tracks"] = _ids(tracks)
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
