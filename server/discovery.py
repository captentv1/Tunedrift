"""Discovery: songs you don't have yet, chosen from your favourite artists (a song of the day and a short list,
the same all day), and the new albums and singles of the artists you follow."""
from __future__ import annotations

import random
import re
import threading
import time
import unicodedata


def _norm(s) -> str:
    s = unicodedata.normalize("NFKD", str(s or "")).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\s*[\(\[].*?[\)\]]", "", s)
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def song_key(title, artist) -> str:
    return _norm(title) + "|" + _norm(re.split(r",|&| feat\.? | ft\.? ", str(artist or ""))[0])


def daily(store, top_artists: list[str], have: set[str], day: str, api=None) -> dict:
    """Up to 12 songs for today, by artists close to the ones you listen to most, none of them in your music.
    Kept for the whole day (the same list until midnight)."""
    with store.lock:
        cached = store.data.get("daily") or {}
    if cached.get("day") == day and cached.get("artists") == top_artists[:5] and cached.get("songs"):
        return cached
    if api is None:
        import artists as api_artists
        import discover as api_discover
        api = (api_artists, api_discover)
    a_mod, d_mod = api
    rnd = random.Random(day)
    picks, seen = [], set()
    for name in top_artists[:5]:
        try:
            found = a_mod.search_artists(name, 3)
            if not found:
                continue
            aid = next((x["id"] for x in found if _norm(x["name"]) == _norm(name)), found[0]["id"])
            radio = d_mod.mix(aid)
        except Exception:
            continue
        rnd.shuffle(radio)
        n = 0
        for t in radio:
            k = song_key(t.get("title"), t.get("uploader"))
            if k in have or k in seen or _norm(t.get("uploader")) == _norm(name):
                continue
            seen.add(k)
            picks.append(dict(t, because=name))
            n += 1
            if n >= 4:
                break
    rnd.shuffle(picks)
    out = {"day": day, "artists": top_artists[:5], "songs": picks[:12], "at": int(time.time())}
    with store.lock:
        store.data["daily"] = out
        store.save()
    return out


class Releases:
    """New albums and singles (out in the last 60 days) of the followed artists, checked at most every 6 hours."""
    EVERY = 6 * 3600
    DAYS = 60

    def __init__(self, store):
        self.store = store
        self.running = False

    def view(self) -> dict:
        with self.store.lock:
            r = self.store.data.get("releases") or {}
            return {"items": list(r.get("items") or []), "checked": r.get("checked", 0), "running": self.running}

    def check(self, follows: list[dict], force=False, get=None) -> dict:
        v = self.view()
        if self.running or not follows or (not force and time.time() - v["checked"] < self.EVERY):
            return v
        self.running = True
        threading.Thread(target=self._run, args=(follows, get), daemon=True).start()
        return self.view()

    def _run(self, follows, get=None):
        try:
            if get is None:
                import artists
                get = artists._get
            limit = time.strftime("%Y-%m-%d", time.localtime(time.time() - self.DAYS * 86400))
            with self.store.lock:
                old = {i["id"]: i for i in (self.store.data.get("releases") or {}).get("items") or []}
            items = []
            for a in follows[:60]:
                try:
                    d = get(f"/artist/{int(a['id'])}/albums?limit=15")
                except Exception:
                    continue
                for x in d.get("data") or []:
                    date = x.get("release_date") or ""
                    if date < limit or date > time.strftime("%Y-%m-%d", time.localtime(time.time() + 86400)):
                        continue
                    items.append({"id": x["id"], "title": x.get("title") or "", "type": x.get("record_type") or "album",
                                  "date": date, "cover": x.get("cover_medium") or "", "artist": a.get("name") or "",
                                  "artist_id": a["id"], "new": x["id"] not in old or old[x["id"]].get("new", False)})
            items.sort(key=lambda i: i["date"], reverse=True)
            with self.store.lock:
                self.store.data["releases"] = {"items": items, "checked": int(time.time())}
                self.store.save()
        finally:
            self.running = False

    def seen(self) -> None:
        with self.store.lock:
            r = self.store.data.get("releases") or {}
            for i in r.get("items") or []:
                i["new"] = False
            self.store.save()
