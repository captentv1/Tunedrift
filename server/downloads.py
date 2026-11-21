"""The download queue: songs from a search, an artist or album page, or an imported playlist.

Every song goes through the same chain: is it already on the device? (then it is only linked, never downloaded
again) -> from another MusicFlow Play (local server): the file is copied as it is -> otherwise MusicFlow finds the
best video and downloads it with its tags, cover and lyrics. The queue runs in the app's process, so it goes on
while the page is hidden or the app is closed (Android keeps a notification while it works), and it is saved, so
what was not finished starts again at the next launch. Few songs at once (one on a phone): converting several
songs at the same time while music plays made phones slow and hot."""
from __future__ import annotations

import json
import re
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import mf_bridge

_LIVE = re.compile(r"\b(remix|live|acoustic|instrumental|karaoke|edit|version|mix|cover|sped up|slowed)\b", re.I)
_BRACKETS = re.compile(r"\s*[\(\[][^\)\]]*[\)\]]")
_SUFFIX = re.compile(r"\s+-\s+(.*remaster.*|.*version.*|.*edit.*|.*mix.*|.*live.*|from .*)$", re.I)
_FEAT = re.compile(r"\s+(feat\.?|ft\.?|featuring|with|x|&)\s+.*$", re.I)
_UNSAFE = re.compile(r'[\\/:*?"<>|\x00-\x1f]')

STATUS = {"running": False, "done": 0, "total": 0, "current": ""}  # read by the Android notification


def _key(text_value: str) -> str:
    t = unicodedata.normalize("NFKD", text_value or "")
    t = "".join(c for c in t if not unicodedata.combining(c)).casefold()
    return " ".join(re.sub(r"[^\w]+", " ", t).replace("_", " ").split())


def main_artist(artist_name: str) -> str:
    a = re.split(r"\s*[,;/]\s*", artist_name or "")[0]
    return _FEAT.sub("", a).strip()


def song_key(title_text: str, artist_name: str) -> str:
    """Same song = same title (without "(feat. …)", "- Remastered"…) and same main artist. A remix or a live
    version is another song: its marker stays in the key."""
    title_text = title_text or ""
    markers = sorted({m.lower() for m in _LIVE.findall(" ".join(_BRACKETS.findall(title_text)) + " " +
                                                       " ".join(_SUFFIX.findall(title_text)))} - {"version", "edit"})
    core = _BRACKETS.sub(" ", _SUFFIX.sub("", title_text)).strip() or title_text
    return _key(core) + (" [" + " ".join(markers) + "]" if markers else "") + "|" + _key(main_artist(artist_name))


def seconds(value) -> int:
    if isinstance(value, (int, float)):
        return int(value)
    parts = [p for p in str(value or "").strip().split(":") if p.strip().isdigit()]
    total = 0
    for p in parts:
        total = total * 60 + int(p)
    return total


def safe_name(text_value: str) -> str:
    return " ".join(_UNSAFE.sub(" ", text_value or "").split()).strip(" .")[:150] or "Song"


class Library:
    """What is already on the device: the player's library, looked up by song."""

    def __init__(self, index):
        self.index = index
        self._by_key: dict[str, list[dict]] = {}
        self._count = -1

    def _refresh(self):
        tracks = self.index.all()
        if len(tracks) == self._count:
            return
        by_key: dict[str, list[dict]] = {}
        for t in tracks:
            by_key.setdefault(song_key(t.get("title"), t.get("artist")), []).append(t)
            # A file named only "Title" (no artist tag) still matches by title.
            if not t.get("artist"):
                by_key.setdefault(song_key(t.get("title"), "") , []).append(t)
        self._by_key, self._count = by_key, len(tracks)

    def find(self, title_text: str, artist_name: str, duration_s: int = 0) -> dict | None:
        self._refresh()
        found = self._by_key.get(song_key(title_text, artist_name)) or []
        for t in found:
            if not duration_s or not t.get("length_s") or abs(t["length_s"] - duration_s) <= 12:
                if Path(t["path"]).is_file():
                    return t
        return None

    on_add = None  # called (track) for each new song file (e.g. to fetch its synced lyrics)

    def add(self, file_path) -> dict | None:
        t = self.index.add_file(file_path)
        self._count = -1
        if t and self.on_add:
            try:
                self.on_add(t)
            except Exception:
                pass
        return t


class Queue:
    def __init__(self, data_file: Path, index, settings, on_song=None):
        self.file = Path(data_file)
        self.lock = threading.Lock()
        self.items: list[dict] = []
        self.lib = Library(index)
        self.settings = settings  # JsonStore: settings.data["dl"]
        self.on_song = on_song  # called (item) when a song is on the device (downloaded or already there)
        self.paused = False
        self._working: set[str] = set()
        self._wake = threading.Event()
        self._last_save = 0.0
        try:
            saved = json.loads(self.file.read_text(encoding="utf-8"))
            self.items = [i for i in saved.get("items", []) if isinstance(i, dict) and i.get("id")]
            for i in self.items:
                if i.get("state") == "working":
                    i["state"] = "queued"
        except Exception:
            pass
        self._thread = None

    # ---- settings ----
    def conf(self) -> dict:
        d = {"format": "mp3-320", "parallel": 2, "root": "", "subfolders": True, "m3u": True, "lyrics": True}
        d.update(self.settings.data.get("dl") or {})
        return d

    # ---- queue ----
    def start(self):
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, daemon=True)
            self._thread.start()
        self._wake.set()

    def add(self, songs: list[dict], folder: str = "", import_id: str = "") -> list[dict]:
        added = []
        with self.lock:
            waiting = {(i.get("key"), i.get("folder")) for i in self.items if i["state"] in ("queued", "working")}
            for s in songs:
                title_text = str(s.get("title") or "").strip()
                if not title_text and not s.get("url"):
                    continue
                artist_name = str(s.get("artist") or s.get("uploader") or "").strip()
                item = {"id": uuid.uuid4().hex[:12], "title": title_text, "artist": artist_name,
                        "album": str(s.get("album") or ""), "duration": seconds(s.get("duration") or s.get("length_s")),
                        "cover": str(s.get("cover") or s.get("thumbnail") or ""), "url": str(s.get("url") or ""),
                        "file_url": str(s.get("file_url") or ""), "ext": str(s.get("ext") or ""),
                        "folder": folder, "import": import_id, "state": "queued", "msg": "", "pct": 0,
                        "track": "", "path": "", "added": int(time.time()), "key": song_key(title_text, artist_name)}
                if (item["key"], folder) in waiting:
                    continue
                waiting.add((item["key"], folder))
                self.items.append(item)
                added.append(item)
            self._trim()
        self._save(force=True)
        self.start()
        return added

    def _trim(self):
        # Keep every song still to do and the last 400 finished ones.
        done = [i for i in self.items if i["state"] not in ("queued", "working")]
        if len(done) > 400:
            drop = {i["id"] for i in done[:len(done) - 400]}
            self.items = [i for i in self.items if i["id"] not in drop]

    def snapshot(self) -> dict:
        with self.lock:
            items = [dict(i) for i in self.items[-500:]]
        counts = {}
        for i in self.items:
            counts[i["state"]] = counts.get(i["state"], 0) + 1
        return {"items": items, "counts": counts, "paused": self.paused, "settings": self.conf()}

    def control(self, action: str, item_id: str = "") -> None:
        with self.lock:
            targets = [i for i in self.items if not item_id or i["id"] == item_id]
            if action == "pause":
                self.paused = True
            elif action == "resume":
                self.paused = False
            elif action == "retry":
                for i in targets:
                    if i["state"] in ("error", "stopped"):
                        i.update(state="queued", msg="", pct=0)
                self.paused = False
            elif action == "stop":
                for i in targets:
                    if i["state"] == "queued":
                        i.update(state="stopped", msg="Stopped")
                    elif i["state"] == "working":
                        i["stop"] = True
            elif action == "clear":
                keep = ("queued", "working")
                self.items = [i for i in self.items if i["state"] in keep or (item_id and i["id"] != item_id)]
            elif action == "remove":
                self.items = [i for i in self.items if i["id"] != item_id or i["state"] == "working"]
        self._save(force=True)
        self._wake.set()

    def _save(self, force=False):
        now = time.time()
        if not force and now - self._last_save < 3:
            return
        self._last_save = now
        with self.lock:
            text_value = json.dumps({"items": self.items}, ensure_ascii=False)
        try:
            tmp = self.file.with_suffix(".tmp")
            tmp.write_text(text_value, encoding="utf-8")
            tmp.replace(self.file)
        except OSError:
            pass

    def _next(self) -> dict | None:
        with self.lock:
            if self.paused or len(self._working) >= max(1, min(4, int(self.conf().get("parallel") or 1))):
                return None
            busy_keys = {i["key"] for i in self.items if i["state"] == "working"}
            for i in self.items:
                # The same song twice (two playlists): the second waits, then is only linked.
                if i["state"] == "queued" and i["key"] not in busy_keys:
                    i["state"] = "working"
                    i["msg"] = "Starting…"
                    self._working.add(i["id"])
                    return i
        return None

    def _status(self):
        with self.lock:
            todo = sum(1 for i in self.items if i["state"] in ("queued", "working"))
            cur = next((i for i in self.items if i["state"] == "working"), None)
        if todo and not STATUS["running"]:
            STATUS.update(running=True, done=0)
        elif not todo and STATUS["running"]:
            STATUS.update(running=False)
        STATUS["total"] = STATUS["done"] + todo
        STATUS["current"] = f"{cur['title']} - {cur['artist']}".strip(" -") if cur else ""

    def _run(self):
        while True:
            self._status()
            item = self._next()
            if item is None:
                self._save()
                self._wake.wait(1.0)
                self._wake.clear()
                continue
            threading.Thread(target=self._work, args=(item,), daemon=True).start()

    def _set(self, item, **kw):
        with self.lock:
            item.update(kw)
        self._save()

    def _work(self, item):
        try:
            self._one(item)
        except Exception as exc:
            self._set(item, state="error", msg=f"{type(exc).__name__}: {exc}"[:300])
        finally:
            with self.lock:
                self._working.discard(item["id"])
                item.pop("stop", None)
            if STATUS["running"]:
                STATUS["done"] += 1
            self._save(force=True)
            self._wake.set()

    def _folder(self, item) -> Path:
        root = item.get("folder") or self.conf().get("root") or mf_bridge.default_dest()
        if not root:
            raise RuntimeError("Choose the download folder in Import › Folder.")
        d = Path(root)
        d.mkdir(parents=True, exist_ok=True)
        return d

    def _one(self, item):
        have = self.lib.find(item["title"], item["artist"], item["duration"]) if item["title"] else None
        if have:
            self._set(item, state="have", msg="Already on the device", track=have["id"], path=have["path"], pct=100)
            self._done(item)
            return
        folder = self._folder(item)
        if item.get("file_url"):
            return self._copy(item, folder)
        if not mf_bridge.wait(90):
            raise RuntimeError("search and download are not available: " + (mf_bridge.STATE["error"] or "not started"))
        url = item["url"]
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        # Only a video or SoundCloud address is downloaded as it is; a Spotify, Deezer or Apple Music song is
        # searched first (best version, length checked) by MusicFlow.
        if not re.search(r"(^|\.)(youtube\.com|youtu\.be|soundcloud\.com)$", host):
            self._set(item, msg="Looking for the best version…")
            code, r = mf_bridge.call("resolve-track", {"query": f"{item['title']} {main_artist(item['artist'])}".strip(),
                                                       "title": item["title"], "artist": item["artist"],
                                                       "duration": item["duration"]})
            if code != 200 or not (r.get("result") or {}).get("url"):
                raise RuntimeError(r.get("error") or "Not found online.")
            url = r["result"]["url"]
            if not item["title"]:
                item["title"] = r["result"].get("title") or ""
        fmt = self.conf().get("format") or "mp3-320"
        quality = {"mp3-320": "320", "mp3-192": "192", "mp3-128": "128"}.get(fmt, fmt)
        name = safe_name(f"{item['title']} - {item['artist']}" if item["artist"] else item["title"])
        code, r = mf_bridge.call("download", {
            "url": url, "save_name": name, "folder": str(folder), "quality": quality, "track_title": item["title"],
            "artist": item["artist"], "cover": item["cover"], "duration": item["duration"] or "",
            "album": item["album"], "lyrics": self.conf().get("lyrics", True), "lyrics_file": self.conf().get("lyrics", True)})
        if code != 200 or not r.get("job_id"):
            raise RuntimeError(r.get("error") or "The download did not start.")
        job = r["job_id"]
        self._set(item, msg="Downloading…")
        while True:
            time.sleep(1.0)
            if item.get("stop"):
                mf_bridge.call("control/" + job, {"action": "stop"})
            code, st = mf_bridge.call("status/" + job, method="GET")
            log = st.get("log") or []
            last = str(log[-1]) if log else ""
            m = re.search(r"(\d+(?:\.\d+)?)%", last)
            self._set(item, msg=last[:160] or "Downloading…", pct=float(m.group(1)) if m else item.get("pct", 0))
            if st.get("status") in ("done", "error", "stopped") or code == 404:
                break
        if item.get("stop") and st.get("status") != "done":
            self._set(item, state="stopped", msg="Stopped")
            return
        if st.get("status") != "done" or not st.get("file") or not Path(str(st["file"])).is_file():
            raise RuntimeError((log[-1] if log else "") or "Download failed.")
        t = self.lib.add(st["file"])
        self._set(item, state="done", msg="Downloaded", pct=100, track=(t or {}).get("id", ""), path=str(st["file"]))
        self._done(item)

    def _copy(self, item, folder: Path):
        """From another MusicFlow Play (e.g. the computer): the file itself, no search, no conversion."""
        u = urllib.parse.urlsplit(item["file_url"])
        if u.scheme not in ("http", "https"):
            raise RuntimeError("Wrong address.")
        ext = item.get("ext") or "mp3"
        ext = ext if re.fullmatch(r"[a-z0-9]{2,4}", ext) else "mp3"
        target = folder / f"{safe_name(item['title'] + (' - ' + item['artist'] if item['artist'] else ''))}.{ext}"
        if target.is_file():
            t = self.lib.add(target)
            self._set(item, state="have", msg="Already in the folder", pct=100, track=(t or {}).get("id", ""), path=str(target))
            self._done(item)
            return
        part = target.with_name(target.name + ".part")
        self._set(item, msg="Copying from the other device…")
        req = urllib.request.Request(item["file_url"], headers={"User-Agent": "MusicFlowPlay"})
        with urllib.request.urlopen(req, timeout=30) as r, open(part, "wb") as f:
            total, got = int(r.headers.get("Content-Length") or 0), 0
            while chunk := r.read(1 << 16):
                if item.get("stop"):
                    break
                f.write(chunk)
                got += len(chunk)
                if total:
                    self._set(item, pct=round(got * 100 / total, 1))
        if item.get("stop"):
            part.unlink(missing_ok=True)
            self._set(item, state="stopped", msg="Stopped")
            return
        part.replace(target)
        lrc = item.get("lrc_url")
        if lrc:
            try:
                with urllib.request.urlopen(lrc, timeout=15) as r:
                    target.with_suffix(".lrc").write_bytes(r.read())
            except Exception:
                pass
        t = self.lib.add(target)
        self._set(item, state="done", msg="Copied", pct=100, track=(t or {}).get("id", ""), path=str(target))
        self._done(item)

    def _done(self, item):
        if self.on_song:
            try:
                self.on_song(item)
            except Exception:
                pass
