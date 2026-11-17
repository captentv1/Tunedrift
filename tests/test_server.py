import importlib
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
FFMPEG = shutil.which("ffmpeg")
pytestmark = pytest.mark.skipif(not FFMPEG, reason="ffmpeg is needed to make test songs")

PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010802000000907753de0000000c4944415408d763f8cfc000000301010018dd8db00000000049454e44ae426082")


def _song(path: Path, seconds=3):
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
                    "-c:a", "libmp3lame", "-b:a", "64k", str(path)], check=True)


@pytest.fixture()
def client(tmp_path, monkeypatch):
    music = tmp_path / "Music"
    (music / "Album A").mkdir(parents=True)
    monkeypatch.setenv("MFP_DATA_DIR", str(tmp_path / "data"))
    sys.path.insert(0, str(ROOT / "server"))
    for name in ("mfp_server", "paths", "library_index", "collections_store"):
        sys.modules.pop(name, None)
    app_module = importlib.import_module("mfp_server")
    import tag_quality
    first = music / "Album A" / "01 First.mp3"
    _song(first)
    tag_quality.write_value(first, {"song_title": "First Song", "artist_name": "Artiste Été, Guest", "album": "Album A",
                                    "track_info": "1/2", "year": "2024-03-01"})
    tag_quality.cover_art(first, PNG, "image/png")
    first.with_suffix(".lrc").write_text("[00:00.50]Hello\n[00:01.50]World\n", encoding="utf-8")
    _song(music / "Untagged - Other Artist.mp3")
    (music / "notes.txt").write_text("not audio")
    app_module.settings.data["folders"] = [str(music)]
    app_module.index.scan([str(music)])
    app_module.app.config["TESTING"] = True
    c = app_module.app.test_client()
    c.environ_base["HTTP_HOST"] = "127.0.0.1:5091"
    c.environ_base["REMOTE_ADDR"] = "127.0.0.1"
    c.mod = app_module
    c.h = {"X-MFP-Token": app_module.CONFIG["token"], "Host": "127.0.0.1:5091"}
    yield c
    sys.path.remove(str(ROOT / "server"))


def _tracks(c):
    return {t["title"]: t for t in c.get("/api/tracks", headers=c.h).get_json()["tracks"]}


def test_token_and_host_are_required(client):
    assert client.get("/api/tracks", headers={"Host": "127.0.0.1:5091"}).status_code == 403
    assert client.get("/api/tracks", headers={**client.h, "Host": "evil.example:5091"}).status_code == 403
    assert client.get("/api/tracks?t=" + client.mod.CONFIG["token"], headers={"Host": "127.0.0.1:5091"}).status_code == 200


def test_page_has_token_only_for_this_computer(client):
    assert client.mod.CONFIG["token"] in client.get("/", headers={"Host": "127.0.0.1:5091"}).get_data(as_text=True)
    client.mod.CONFIG["lan"] = True
    try:
        page = client.get("/", headers={"Host": "192.168.1.20:5091"}, environ_base={"REMOTE_ADDR": "192.168.1.30"})
        assert page.status_code == 200 and client.mod.CONFIG["token"] not in page.get_data(as_text=True)
    finally:
        client.mod.CONFIG["lan"] = False


def test_library_reads_tags_subfolders_and_file_names(client):
    t = _tracks(client)
    assert set(t) == {"First Song", "Untagged"}
    first = t["First Song"]
    assert first["artists"] == ["Artiste Été", "Guest"] and first["album"] == "Album A"
    assert first["track_no"] == 1 and first["year"] == "2024" and first["cover"] and first["lrc"]
    assert t["Untagged"]["artist"] == "Other Artist"


def test_audio_supports_range_requests(client):
    tid = _tracks(client)["First Song"]["id"]
    r = client.get(f"/api/audio/{tid}", headers={**client.h, "Range": "bytes=0-99"})
    assert r.status_code == 206 and len(r.data) == 100 and r.mimetype == "audio/mpeg"
    assert client.get("/api/audio/0000000000000000", headers=client.h).status_code == 404


def test_cover_and_synced_lyrics(client):
    t = _tracks(client)
    r = client.get(f"/api/cover/{t['First Song']['id']}", headers=client.h)
    assert r.status_code == 200 and r.data == PNG and r.mimetype == "image/png"
    assert client.get(f"/api/cover/{t['Untagged']['id']}", headers=client.h).status_code == 404
    ly = client.get(f"/api/lyrics/{t['First Song']['id']}", headers=client.h).get_json()
    assert ly["source"] == "file" and "[00:01.50]World" in ly["synced"] and ly["plain"] == "Hello\nWorld"


def test_online_lyrics_are_cached_not_written(client, monkeypatch):
    tid = _tracks(client)["Untagged"]["id"]
    monkeypatch.setattr(client.mod.lyrics, "find_items", lambda *a: {"plain": "", "synced": "[00:00.10]La"})
    r = client.post(f"/api/lyrics/{tid}/find", headers=client.h, json={})
    assert r.get_json()["synced"] == "[00:00.10]La"
    assert client.get(f"/api/lyrics/{tid}", headers=client.h).get_json()["source"] == "online"
    assert not _tracks(client)["Untagged"]["lrc"]


def test_favorites_and_playlists(client):
    t = _tracks(client)
    a, b = t["First Song"]["id"], t["Untagged"]["id"]
    assert client.post("/api/favorites", headers=client.h, json={"id": a}).get_json()["favorites"] == [a]
    p = client.post("/api/playlists", headers=client.h, json={"name": "Road trip", "tracks": [a]}).get_json()["playlist"]
    p = client.post(f"/api/playlists/{p['id']}", headers=client.h, json={"add": [b, a]}).get_json()["playlist"]
    assert p["tracks"] == [a, b]
    p = client.post(f"/api/playlists/{p['id']}", headers=client.h, json={"name": "Trip", "tracks": [b]}).get_json()["playlist"]
    assert p["name"] == "Trip" and p["tracks"] == [b]
    saved = client.get("/api/collections", headers=client.h).get_json()
    assert saved["favorites"] == [a] and saved["playlists"][0]["name"] == "Trip"
    assert client.delete(f"/api/playlists/{p['id']}", headers=client.h).status_code == 200
    assert client.post("/api/favorites", headers=client.h, json={"id": a, "on": False}).get_json()["favorites"] == []


def test_rescan_uses_cache_and_sees_changes(client, tmp_path):
    music = Path(client.mod.settings.data["folders"][0])
    _song(music / "New One.mp3")
    client.mod.index.scan([str(music)])
    assert "New One" in _tracks(client)
    (music / "New One.mp3").unlink()
    client.mod.index.scan([str(music)])
    assert "New One" not in _tracks(client)


def test_folders_must_exist(client, tmp_path):
    r = client.post("/api/folders", headers=client.h, json={"folders": [str(tmp_path / "nope")]})
    assert r.status_code == 404


def test_browse_lists_subfolders(client):
    music = Path(client.mod.settings.data["folders"][0])
    roots = client.get("/api/browse", headers=client.h).get_json()
    assert roots["folders"] and roots["parent"] is None
    d = client.get("/api/browse", headers=client.h, query_string={"path": str(music)}).get_json()
    assert [f["name"] for f in d["folders"]] == ["Album A"] and d["parent"] == str(music.parent)
    assert client.get("/api/browse", headers=client.h, query_string={"path": str(music / "nope")}).status_code == 404


def test_app_mode_keeps_token_out_of_the_page(client):
    client.mod.CONFIG["token_in_page"] = False
    try:
        assert client.mod.CONFIG["token"] not in client.get("/", headers={"Host": "127.0.0.1:5091"}).get_data(as_text=True)
    finally:
        client.mod.CONFIG["token_in_page"] = True


def _wait_module(me, states=("installed", "error")):
    import time
    for _ in range(100):
        if me.MODULE["state"] in states:
            return me.MODULE
        time.sleep(0.05)
    return me.MODULE


def test_download_tab_module_installs_from_a_zip(tmp_path, monkeypatch):
    import json
    import zipfile
    sys.path.insert(0, str(ROOT / "server"))
    monkeypatch.setenv("MFP_DATA_DIR", str(tmp_path / "data"))
    for name in ("musicflow_embed", "paths"):
        sys.modules.pop(name, None)
    me = importlib.import_module("musicflow_embed")
    started = []
    me.STARTER = lambda: started.append(True)

    good = tmp_path / "good.zip"
    with zipfile.ZipFile(good, "w") as z:
        z.writestr("module.json", json.dumps({"version": "9.9"}))
        z.writestr("app.py", "")
    me.install(good.as_uri())
    assert _wait_module(me)["state"] == "installed" and me.module_version() == "9.9" and started

    # Security: a file that would land outside the module's folder is refused.
    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("module.json", "{}")
        z.writestr("../evil.py", "")
    me.MODULE["state"] = "missing"
    me.install(bad.as_uri())
    assert _wait_module(me)["state"] == "error" and not (tmp_path / "data" / "evil.py").exists()
    assert me.module_version() == "9.9"
    sys.path.remove(str(ROOT / "server"))


def test_lyrics_sync_only_takes_checked_lyrics(monkeypatch):
    sys.path[:0] = [str(ROOT / "server"), str(ROOT / "musicflow" / "core")]
    import lyrics
    import lyrics_sync
    synced = "[00:01.00]Hello\n[03:15.00]End"
    good = {"trackName": "Blinding Lights", "artistName": "The Weeknd", "duration": 200, "syncedLyrics": synced, "plainLyrics": "Hello\nEnd"}
    answers = {"get": None, "search": []}
    monkeypatch.setattr(lyrics, "_get", lambda url: answers["get" if "/get?" in url else "search"])

    answers["get"] = good
    assert lyrics_sync.find("Blinding Lights (Remastered)", "The Weeknd, Guest", 201)["synced"] == synced
    # Wrong song, wrong artist, other length, no synced lyrics, timings past the end: refused.
    for bad in ({"trackName": "Save Your Tears"}, {"artistName": "Someone Else"}, {"duration": 260},
                {"syncedLyrics": ""}, {"syncedLyrics": "[00:01.00]a\n[04:00.00]b"}):
        answers["get"] = dict(good, **bad)
        assert lyrics_sync.find("Blinding Lights", "The Weeknd", 200) is None, bad
    answers["get"], answers["search"] = None, [dict(good, duration=150), good]
    assert lyrics_sync.find("Blinding Lights", "The Weeknd", 199)
    for p in (str(ROOT / "server"), str(ROOT / "musicflow" / "core")):
        sys.path.remove(p)


def test_lyrics_sync_replaces_the_old_lyrics(client, monkeypatch):
    import time
    import lyrics_sync
    new = {"synced": "[00:00.20]New words", "plain": "New words"}
    monkeypatch.setattr(lyrics_sync, "find", lambda title, artist, length: new if title == "First Song" else None)
    assert client.post("/api/lyrics-sync", json={}, headers=client.h).status_code == 200
    for _ in range(100):
        st = client.get("/api/lyrics-sync", headers=client.h).get_json()
        if not st["running"]:
            break
        time.sleep(0.05)
    assert (st["total"], st["updated"], st["not_found"]) == (2, 1, 1)
    tid = next(t["id"] for t in client.get("/api/tracks", headers=client.h).get_json()["tracks"] if t["title"] == "First Song")
    got = client.get(f"/api/lyrics/{tid}", headers=client.h).get_json()
    assert got["synced"] == new["synced"]
    path = next(t["path"] for t in client.mod.index.all() if t["id"] == tid)
    assert Path(path).with_suffix(".lrc").read_text(encoding="utf-8") == new["synced"]


def test_delete_song_removes_the_file_and_its_lyrics(client, tmp_path):
    t = _tracks(client)["First Song"]
    f = Path(t["path"])
    assert t["size_bytes"] > 0
    r = client.delete(f"/api/tracks/{t['id']}", headers=client.h)
    assert r.status_code == 200
    assert not f.exists() and not f.with_suffix(".lrc").exists()
    assert "First Song" not in _tracks(client)
    assert client.delete(f"/api/tracks/{t['id']}", headers=client.h).status_code == 404


def test_album_fix_finds_the_checked_album(monkeypatch):
    import album_fix
    import artists
    hits = {"data": [
        {"id": 1, "title": "Papaoutai", "duration": 232, "artist": {"name": "Pomme"}, "album": {"title": "Other"}},
        {"id": 2, "title": "Papaoutai", "duration": 300, "artist": {"name": "Stromae"}, "album": {"title": "Live"}},
        {"id": 3, "title": "Papaoutai", "duration": 232, "artist": {"name": "Stromae"}, "album": {"title": "Best of hits"}},
        {"id": 4, "title": "Papaoutai", "duration": 233, "artist": {"name": "Stromae"}, "album": {"title": "Racine carrée"}}]}
    track = {"track_position": 2, "release_date": "2013-08-16", "artist": {"name": "Stromae"}, "album": {"title": "Racine carrée"}}
    monkeypatch.setattr(artists, "_get", lambda p: hits if p.startswith("/search") else track)
    assert album_fix.find("Papaoutai (feat. X)", "Stromae", 232, "") == {
        "album": "Racine carrée", "album_artist": "Stromae", "track_info": "2", "year": "2013", "cover": ""}
    assert album_fix.find("Papaoutai", "Stromae", 232, "Racine Carrée") == {}
    assert album_fix.find("Papaoutai", "Angèle", 232, "") is None


def test_edit_song_info_and_details(client):
    t = _tracks(client)["First Song"]
    r = client.post("/api/tags", headers=client.h, json={"ids": [t["id"]], "fields": {
        "title": "Premier", "artist": "Nouveau", "album": "Court", "album_artist": "Nouveau", "year": "2020", "genre": "Pop", "track_no": "5"}})
    assert r.status_code == 200 and r.get_json()["done"] == 1
    e = _tracks(client)["Premier"]
    assert (e["artist"], e["album"], e["album_artist"], e["year"], e["genre"], e["track_no"]) == ("Nouveau", "Court", "Nouveau", "2020", "Pop", 5)
    assert e["id"] == t["id"] and e["cover"]
    assert client.post("/api/tags", headers=client.h, json={"ids": [t["id"]], "fields": {"title": " "}}).status_code == 400
    d = client.get(f"/api/tracks/{t['id']}/details", headers=client.h).get_json()
    assert d["file"] == "01 First.mp3" and d["format"] == "mp3" and d["sample_rate"] and d["bitrate"]
    assert d["tags"]["album"] == "Court" and d["cover"]["mime"] == "image/png" and d["lyrics"] == "synced (.lrc)"


def test_shorten_album_names():
    import album_fix
    assert album_fix.shorten("Titanic (Original Motion Picture Soundtrack)") == "Titanic"
    assert album_fix.shorten("Les Misérables - Bande originale du film") == "Les Misérables"
    assert album_fix.shorten("21 [Deluxe Edition] (Remastered)") == "21"
    assert album_fix.shorten("Racine carrée") == "Racine carrée"
    assert album_fix.shorten("(What's the Story) Morning Glory?") == "(What's the Story) Morning Glory?"


def test_album_match_compares_the_track_list(monkeypatch):
    import album_fix
    import artists
    albums = {"data": [{"id": 9, "title": "Encanto (Original Motion Picture Soundtrack)"}, {"id": 8, "title": "Encanto (Karaoke)"}]}
    full = {9: {"title": "Encanto (Original Motion Picture Soundtrack)", "artist": {"name": "Various Artists"}, "release_date": "2021-11-19",
                "cover_xl": "https://e-cdns-images.dzcdn.net/x.jpg", "genres": {"data": [{"name": "Films/Games"}]}, "nb_tracks": 3,
                "tracks": {"data": [{"title": "The Family Madrigal", "duration": 205}, {"title": "We Don't Talk About Bruno", "duration": 216},
                                    {"title": "Surface Pressure", "duration": 182}]}},
            8: {"title": "Encanto (Karaoke)", "nb_tracks": 1, "tracks": {"data": [{"title": "Waiting on a Miracle (Karaoke)", "duration": 200}]}}}
    monkeypatch.setattr(artists, "_get", lambda p: albums if p.startswith("/search") else full[int(p.split("/")[2])])
    local = [{"id": "a", "title": "We Dont Talk About Bruno (From \"Encanto\")", "length_s": 215, "album": "Encanto (Original Motion Picture Soundtrack)", "artist": "Carolina Gaitán"},
             {"id": "b", "title": "Surface Pressure", "length_s": 183, "album": "Encanto (Original Motion Picture Soundtrack)", "artist": "Jessica Darrow"}]
    best = album_fix.match_album(local)[0]
    assert best["id"] == 9 and best["matched"] == 2 and album_fix.sure(local, best)
    assert best["tracks"]["a"]["track_info"] == "2" and best["tracks"]["b"]["track_info"] == "3" and best["year"] == "2021"
