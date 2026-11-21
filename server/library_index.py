from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import threading
from pathlib import Path

import audio_check
import library
import tag_quality

_LRC_LINE = re.compile(r"^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]")
_FOLDER_IMAGES = ("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "album.jpg")
_CACHE_VERSION = 1


def track_id(file_path) -> str:
    return hashlib.sha1(os.path.normcase(str(Path(file_path).resolve())).encode("utf-8")).hexdigest()[:16]


def split_artists(artist_name: str) -> list[str]:
    names = [a.strip() for a in re.split(r"\s*[,;/]\s*|\s+feat\.?\s+|\s+ft\.?\s+", artist_name or "", flags=re.I)]
    return [a for a in dict.fromkeys(names) if a]


def _track_number(value) -> int:
    m = re.match(r"\s*(\d+)", str(value or ""))
    return int(m.group(1)) if m else 0


def _year(value) -> str:
    m = re.match(r"\s*(\d{4})", str(value or ""))
    return m.group(1) if m else ""


def read_track(file_path: Path, st=None) -> dict:
    st = st or file_path.stat()
    tags = tag_quality.read_value(file_path)
    title_text, artist_name = library._title_artist(file_path, tags)
    artists = split_artists(artist_name)
    album_artist = (tags.get("album_artist") or "").strip() or (artists[0] if artists else "")
    return {"id": track_id(file_path), "path": str(file_path), "title": title_text or file_path.stem,
            "artist": artist_name, "artists": artists, "album": (tags.get("album") or "").strip(),
            "album_artist": album_artist, "genre": tags.get("genre") or "", "year": _year(tags.get("year")),
            "track_no": _track_number(tags.get("track_info")), "length_s": tags.get("length_s") or 0,
            "cover": bool(tags.get("cover_art")), "lyrics": bool(tags.get("lyrics_data")),
            "lrc": file_path.with_suffix(".lrc").is_file(), "format": file_path.suffix.lower().lstrip("."),
            "size_bytes": st.st_size, "mtime": int(st.st_mtime)}


class LibraryIndex:
    def __init__(self, cache_path: Path):
        self.cache_path = Path(cache_path)
        self.lock = threading.Lock()
        self.tracks: dict[str, dict] = {}
        self.status = {"running": False, "read_count": 0, "found": 0, "error": ""}
        self._cache: dict[str, dict] = {}
        try:
            raw = json.loads(self.cache_path.read_text(encoding="utf-8"))
            if raw.get("version") == _CACHE_VERSION:
                self._cache = raw.get("files") or {}
                self.tracks = {t["id"]: t for t in self._cache.values()}
        except Exception:
            pass

    def _files(self, folders):
        seen = set()
        for folder_path in folders:
            root = Path(folder_path).expanduser()
            if not root.is_dir():
                continue
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames[:] = [d for d in dirnames if not d.startswith((".", "$"))]
                for name in filenames:
                    if name.lower().endswith(audio_check.EXTENSIONS_AUDIO):
                        f = Path(dirpath) / name
                        key = os.path.normcase(str(f))
                        if key not in seen:
                            seen.add(key)
                            yield f

    def scan(self, folders) -> None:
        with self.lock:
            if self.status["running"]:
                return
            self.status = {"running": True, "read_count": 0, "found": 0, "error": ""}
        fresh: dict[str, dict] = {}
        try:
            for f in self._files(folders):
                try:
                    st = f.stat()
                except OSError:
                    continue
                key = str(f)
                old = self._cache.get(key)
                if old and old.get("mtime") == int(st.st_mtime) and old.get("size_bytes") == st.st_size:
                    old["lrc"] = f.with_suffix(".lrc").is_file()
                    fresh[key] = old
                else:
                    try:
                        fresh[key] = read_track(f, st)
                    except Exception:
                        continue
                self.status["read_count"] += 1
                self.status["found"] = len(fresh)
        except Exception as exc:
            self.status["error"] = str(exc)
        with self.lock:
            self._cache = fresh
            self.tracks = {t["id"]: t for t in fresh.values()}
            self.status["running"] = False
        self._save()

    def _save(self) -> None:
        tmp = self.cache_path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps({"version": _CACHE_VERSION, "files": self._cache}, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.cache_path)
        except Exception:
            pass

    def get(self, tid: str) -> dict | None:
        t = self.tracks.get(tid)
        if t and Path(t["path"]).is_file():
            return t
        return None

    def refresh(self, tid: str) -> dict | None:
        t = self.get(tid)
        if not t:
            return None
        f = Path(t["path"])
        fresh = read_track(f)
        with self.lock:
            self._cache[str(f)] = fresh
            self.tracks[tid] = fresh
        self._save()
        return fresh

    def add_file(self, file_path) -> dict | None:
        """Adds (or reads again) one file, e.g. a song just downloaded, without reading the folders again."""
        f = Path(file_path)
        try:
            fresh = read_track(f)
        except Exception:
            return None
        with self.lock:
            self._cache[str(f)] = fresh
            self.tracks[fresh["id"]] = fresh
        self._save()
        return fresh

    def remove(self, tid: str) -> None:
        with self.lock:
            t = self.tracks.pop(tid, None)
            if t:
                self._cache.pop(str(Path(t["path"])), None)
        self._save()

    def all(self) -> list[dict]:
        with self.lock:
            return list(self.tracks.values())


def cover_bytes(file_path) -> tuple[bytes, str] | None:
    import mutagen
    f_path = Path(file_path)
    try:
        f = mutagen.File(str(f_path))
        t = f.tags if f else None
        if getattr(f, "pictures", None):
            p = f.pictures[0]
            return p.data, p.mime or "image/jpeg"
        if t is not None:
            for k in list(t.keys()):
                if str(k).startswith("APIC"):
                    return t[k].data, t[k].mime or "image/jpeg"
            if "covr" in t:
                c = t["covr"][0]
                return bytes(c), "image/png" if getattr(c, "imageformat", 0) == 14 else "image/jpeg"
            if "METADATA_BLOCK_PICTURE" in t:
                from mutagen.flac import Picture
                p = Picture(base64.b64decode(t["METADATA_BLOCK_PICTURE"][0]))
                return p.data, p.mime or "image/jpeg"
    except Exception:
        pass
    for name in _FOLDER_IMAGES:
        img = f_path.parent / name
        if img.is_file():
            return img.read_bytes(), "image/png" if name.endswith(".png") else "image/jpeg"
    return None


def embedded_lyrics(file_path) -> str:
    try:
        import mutagen
        f = mutagen.File(str(file_path))
        t = f.tags if f else None
        if t is None:
            return ""
        for k in list(t.keys()):
            if str(k).startswith("USLT"):
                return str(t[k].text)
        for k in ("LYRICS", "UNSYNCEDLYRICS", "\xa9lyr"):
            if k in t:
                return str(t[k][0])
    except Exception:
        pass
    return ""


def is_synced(text_value: str) -> bool:
    lines = [l for l in (text_value or "").splitlines() if l.strip()]
    return bool(lines) and sum(1 for l in lines if _LRC_LINE.match(l)) >= max(1, len(lines) // 2)


def plain_from_synced(synced: str) -> str:
    out = []
    for line in synced.splitlines():
        text_value = re.sub(r"^(\s*\[[^\]]*\])+", "", line).strip()
        if text_value or out:
            out.append(text_value)
    return "\n".join(out).strip()


def file_lyrics(file_path) -> dict:
    f = Path(file_path)
    lrc = f.with_suffix(".lrc")
    synced = ""
    if lrc.is_file():
        try:
            synced = lrc.read_text(encoding="utf-8", errors="replace")
        except Exception:
            synced = ""
    embedded = embedded_lyrics(f)
    if not synced and is_synced(embedded):
        synced = embedded
    plain = embedded if embedded and not is_synced(embedded) else (plain_from_synced(synced) if synced else "")
    return {"synced": synced, "plain": plain}
