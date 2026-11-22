"""Import and download queue: songs already on the device are linked, never downloaded again; playlists from
files, lists of titles and another Playwave (local server)."""
import threading
import time
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "musicflow" / "core")]
from test_server import _song, client  # noqa: E402,F401  (fixture)


def _wait(fn, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(0.1)
    return fn()


def test_song_key_ignores_feat_and_remaster_but_not_remix():
    import downloads as d
    assert d.song_key("Song (feat. B)", "Artist A, B") == d.song_key("Song", "Artist A")
    assert d.song_key("Song - Remastered 2011", "Artist A") == d.song_key("Song", "artist a")
    assert d.song_key("Song (Remix)", "Artist A") != d.song_key("Song", "Artist A")
    assert d.song_key("Été", "Zaz") == d.song_key("Ete", "ZAZ")


def test_parse_m3u_csv_and_text():
    import importer
    name, t = importer.parse_text("#EXTM3U\n#EXTINF:215,Daft Punk - One More Time\nmusic/a.mp3\nC:\\x\\Zaz - Je veux.flac\n", "Road.m3u8")
    assert name == "Road" and t[0] == {"title": "One More Time", "artist": "Daft Punk", "duration": 215}
    assert t[1]["title"] == "Je veux" and t[1]["artist"] == "Zaz"
    csv_text = '"Track URI","Track Name","Artist Name(s)","Album Name","Duration (ms)"\n"x","Halo","Beyoncé","I Am","261640"\n'
    _, t = importer.parse_text(csv_text, "liked.csv")
    assert t == [{"title": "Halo", "artist": "Beyoncé", "album": "I Am", "duration": 261}]
    _, t = importer.parse_text("Stromae - Alors on danse\nJust a title\n")
    assert t[0]["artist"] == "Stromae" and t[1] == {"title": "Just a title", "artist": "", "duration": 0}


def test_youtube_titles_become_songs():
    import importer
    assert importer.youtube_song("Stromae - Papaoutai (Official Video)", "StromaeVEVO") == ("Papaoutai", "Stromae")
    assert importer.youtube_song("Halo", "Beyoncé - Topic") == ("Halo", "Beyoncé")


def test_import_links_songs_already_on_the_device(client, tmp_path):
    root = tmp_path / "Root"
    h = client.h
    assert client.post("/api/dl/settings", json={"root": str(root)}, headers=h).status_code == 200
    assert str(root) in client.mod.settings.data["folders"]
    text = "Other Artist - Untagged\nArtiste Été - First Song\n"
    r = client.post("/api/import/preview", json={"source": {"kind": "text", "text": text}}, headers=h).get_json()
    assert r["have"] == 2
    r = client.post("/api/import/start", json={"source": {"kind": "text", "text": text}, "name": "Mine"}, headers=h).get_json()
    imp = r["import"]
    assert imp["folder"] == str(root / "Mine")
    q = client.mod.queue
    _wait(lambda: all(i["state"] == "have" for i in q.items) and len(q.items) == 2)
    assert [i["state"] for i in q.items] == ["have", "have"]
    pl = _wait(lambda: next((p for p in client.mod.collections.snapshot()["playlists"] if p["id"] == imp["playlist"] and len(p["tracks"]) == 2), None))
    titles = [client.mod.index.get(t)["title"] for t in pl["tracks"]]
    assert titles == ["Untagged", "First Song"]  # order of the source
    m3u = _wait(lambda: (root / "Mine" / "Mine.m3u8").is_file() and (root / "Mine" / "Mine.m3u8").read_text().count(".mp3") == 2)
    assert m3u and "../../Music/Album A/01 First.mp3" in (root / "Mine" / "Mine.m3u8").read_text()
    # Nothing was downloaded: no file in the root folder.
    assert not [f for f in root.rglob("*.mp3")]


def test_local_server_copies_files_once(client, tmp_path):
    """The phone imports a playlist of the computer (Playwave started with --lan): the files are copied."""
    from werkzeug.serving import make_server
    import importlib, sys
    other_dir = tmp_path / "pc"
    (other_dir / "Music").mkdir(parents=True)
    _song(other_dir / "Music" / "Remote Song - Far Artist.mp3")
    # A second server: same code, other data folder, LAN mode.
    mod = client.mod
    pc = importlib.util.spec_from_file_location("pc_server", str(Path(mod.__file__)))
    import os
    os.environ["MFP_DATA_DIR"] = str(other_dir / "data")
    pcm = importlib.util.module_from_spec(pc)
    import importer
    saved = dict(importer.CTX)  # both servers share the importer module in this test: keep the phone's
    pc.loader.exec_module(pcm)
    importer.CTX.update(saved)
    mod.queue.on_song = importer.on_song
    pcm.CONFIG.update(lan=True, token="k" * 20)
    pcm.index.scan([str(other_dir / "Music")])
    tid = next(iter(pcm.index.tracks))
    pcm.collections.create("Party", [tid])
    srv = make_server("127.0.0.1", 0, pcm.app, threaded=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    address = f"http://127.0.0.1:{srv.server_port}/?t={'k' * 20}"
    try:
        h = client.h
        client.post("/api/dl/settings", json={"root": str(tmp_path / "Root")}, headers=h)
        r = client.post("/api/import/playlists", json={"kind": "server", "address": address}, headers=h).get_json()
        party = next(p for p in r["playlists"] if p["name"] == "Party")
        src = {"kind": "server", "base": r["base"], "id": party["id"]}
        r = client.post("/api/import/start", json={"source": src}, headers=h).get_json()
        assert "import" in r, r
        imp = r["import"]
        q = client.mod.queue
        _wait(lambda: q.items and q.items[-1]["state"] in ("done", "error"))
        assert q.items[-1]["state"] == "done", q.items[-1]
        copied = Path(q.items[-1]["path"])
        assert copied.is_file() and copied.parent == tmp_path / "Root" / "Party"
        # Updating the import: the song is already there, nothing is copied again.
        assert client.post(f"/api/import/{imp['id']}", json={}, headers=h).status_code == 200
        _wait(lambda: q.items[-1]["state"] != "queued" and q.items[-1]["state"] != "working")
        # Importing the same playlist again updates the same import and playlist.
        r2 = client.post("/api/import/start", json={"source": src}, headers=h).get_json()
        assert r2["import"]["id"] == imp["id"]
        assert [p["name"] for p in client.mod.collections.snapshot()["playlists"]].count("Party") == 1
        st = client.get("/api/import/state", headers=h).get_json()
        assert "token" not in str(st["imports"])  # the key of the other device is not shown
    finally:
        srv.shutdown()


def test_musicflow_routes_are_limited(client):
    assert client.post("/api/mf/download", json={}, headers=client.h).status_code == 403
    assert client.post("/api/mf/../state", json={}, headers=client.h).status_code in (403, 404)


def test_youtube_channel_links():
    import importer
    assert importer.youtube_channel("@Stromae") == "https://www.youtube.com/@Stromae"
    assert importer.youtube_channel("stromae") == "https://www.youtube.com/@stromae"
    assert importer.youtube_channel("https://www.youtube.com/@Stromae/videos") == "https://www.youtube.com/@Stromae"
    assert importer.youtube_channel("music.youtube.com/channel/UC_abc-123") == "https://www.youtube.com/channel/UC_abc-123"
    for bad in ("https://example.com/@x", "https://www.youtube.com/watch?v=abc", ""):
        try:
            importer.youtube_channel(bad)
            raise AssertionError(bad)
        except importer.ImportError_:
            pass


class _FakeBot:
    """SpotifyBot of the phone app, where Spotify refuses its Web API: the lists are read from the page."""
    def connected(self):
        return True

    def token(self, timeout):
        return ""

    def apiBase(self):
        return "http://127.0.0.1:9/v1"

    def webBase(self):
        return "https://open.spotify.com"

    def playlists(self, timeout):
        return '[{"id": "abc123", "name": "Road trip"}, {"id": "abc123", "name": "dup"}, {"id": "", "name": "x"}]'

    def tracks(self, url, timeout):
        assert url == "https://open.spotify.com/playlist/abc123"
        return ('{"done": true, "name": "Road trip", "items": [{"title": "One More Time", "artist": "Daft Punk",'
                ' "album": "Discovery", "duration": "5:20", "cover": ""}, {"title": ""}]}')


def test_spotify_without_key(client, monkeypatch):
    import importer
    st = client.get("/api/import/state", headers=client.h).get_json()
    assert st["spotify"] == {"available": False, "connected": False}  # computer: sign-in is in the phone app
    r = client.post("/api/import/playlists", json={"kind": "spotify"}, headers=client.h)
    assert r.status_code == 400 and "phone" in r.get_json()["error"]
    monkeypatch.setattr(importer, "spotify_bot", lambda: _FakeBot())
    assert client.get("/api/import/state", headers=client.h).get_json()["spotify"]["connected"] is True
    lists = client.post("/api/import/playlists", json={"kind": "spotify"}, headers=client.h).get_json()["playlists"]
    assert [(p["id"], p["name"]) for p in lists] == [("liked", "Liked Songs"), ("abc123", "Road trip")]
    name, tracks = importer.fetch({"kind": "spotify", "id": "abc123"})
    assert name == "Road trip" and tracks == [{"title": "One More Time", "artist": "Daft Punk", "album": "Discovery",
                                               "duration": 320, "cover": "", "url": ""}]


def test_online_search_only_latest_and_cached(monkeypatch):
    """Typing asks for many searches: the ones waiting behind a newer one return at once, answers are kept."""
    import mf_bridge
    calls = []

    def slow(path, payload=None, method="POST"):
        calls.append(payload["title"])
        time.sleep(0.4)
        return 200, {"results": [{"title": payload["title"]}]}
    monkeypatch.setattr(mf_bridge, "call", slow)
    mf_bridge._SEARCH["cache"].clear()
    out = {}
    threads = [threading.Thread(target=lambda q=q: out.__setitem__(q, mf_bridge.search({"title": q, "limit": 15})))
               for q in ("I", "In", "Int", "Internet")]
    for t in threads:
        t.start()
        time.sleep(0.05)
    for t in threads:
        t.join()
    assert out["Internet"] == (200, {"results": [{"title": "Internet"}]})
    assert [q for q, (code, _) in out.items() if code == 409] == ["In", "Int"]  # "I" was already running
    assert calls == ["I", "Internet"]
    assert mf_bridge.search({"title": "Internet", "limit": 15})[0] == 200 and calls == ["I", "Internet"]  # from the cache


def test_synced_lyrics_are_suggested_then_added(client, monkeypatch):
    """Songs without synced lyrics are looked up in the background; nothing is written before the person agrees."""
    import lyrics_sync
    looked = []

    def fake_find(title, artist, length_s):
        looked.append(title)
        return {"synced": "[00:00.20]La la\n[00:01.00]Lo lo", "plain": "La la\nLo lo"}
    monkeypatch.setattr(lyrics_sync, "find", fake_find)
    r = client.get("/api/lyrics-suggest?now=1", headers=client.h).get_json()
    _wait(lambda: not lyrics_sync.SUGGEST["running"])
    d = client.get("/api/lyrics-suggest", headers=client.h).get_json()
    assert "First Song" not in looked  # it already has its .lrc
    assert [i["preview"] for i in d["items"]] == [["La la", "Lo lo"]] and r is not None
    tid = d["items"][0]["id"]
    t = client.mod.index.get(tid)
    assert not Path(t["path"]).with_suffix(".lrc").exists()  # only suggested
    done = client.post("/api/lyrics-suggest", json={"apply": [tid]}, headers=client.h).get_json()
    assert done == {"applied": 1, "count": 0}
    assert "La la" in Path(t["path"]).with_suffix(".lrc").read_text(encoding="utf-8")


def test_spotify_lists_and_songs_kept_and_read_ahead(client, monkeypatch):
    """Opening the app reads the playlists and their songs in the background; the tab then shows them at once."""
    import importer
    calls = []

    class Bot(_FakeBot):
        def partial(self):
            return ""

        def clearPartial(self):
            pass

        def tracks(self, url, timeout):
            calls.append(url)
            return super().tracks(url, timeout)

    monkeypatch.setattr(importer, "spotify_bot", lambda: Bot())
    importer.CTX["spcache"].data.update(playlists=[], at=0, tracks={})
    importer.SPC.update(want="", prefetch=False, list_try=0, error="", tried={}, errors={})
    get = lambda q: client.get("/api/import/spotify" + q, headers=client.h).get_json()
    get("?warm=1")
    deadline = time.time() + 10
    while (importer.SPC["worker"] is not None or "abc123" not in importer.CTX["spcache"].data["tracks"]) and time.time() < deadline:
        time.sleep(0.05)
    d = get("")
    assert [p["id"] for p in d["playlists"]] == ["liked", "abc123"] and d["running"] is False
    n = len(calls)
    d = get("?pid=abc123")  # read ahead: no new reading, the songs are there at once
    assert d["complete"] is True and [t["title"] for t in d["tracks"]] == ["One More Time"] and d["name"] == "Road trip"
    assert len(calls) == n


def test_spotify_radio_goes_to_musicflow(client, monkeypatch):
    """The Radio source asks MusicFlow for the Spotify radio of a song or an artist (its link is then read)."""
    import mf_bridge
    seen = []

    def fake(path, payload=None, method="POST"):
        seen.append((path, payload))
        return 200, {"url": "https://open.spotify.com/playlist/37i9dQZF1E8radio"}
    monkeypatch.setattr(mf_bridge, "call", fake)
    r = client.post("/api/mf/spotify-radio", json={"query": "Stromae", "kind": "artist"}, headers=client.h)
    assert r.status_code == 200 and r.get_json()["url"].endswith("radio")
    assert seen == [("spotify-radio", {"query": "Stromae", "kind": "artist"})]


def test_online_artists_and_their_page(client, monkeypatch):
    """Search › Online › Artists lists artists; picking one opens its whole discography by its id."""
    import artists
    monkeypatch.setattr(artists, "search_artists", lambda q, n=12: [{"id": 75798, "name": "Stromae", "photo": "", "fans": 5, "albums": 3}])
    monkeypatch.setattr(artists, "discography", lambda i: {"artist_name": {"id": i, "name": "Stromae"}, "top": [], "albums": []})
    d = client.get("/api/online/artists?q=strom", headers=client.h).get_json()
    assert [a["name"] for a in d["artists"]] == ["Stromae"]
    d = client.get("/api/online/artist?id=75798", headers=client.h).get_json()
    assert d["artist_name"]["id"] == 75798
