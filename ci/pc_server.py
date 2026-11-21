"""A computer's MusicFlow Play for the emulator test: LAN mode, the given music folder, and a playlist "PC Party"
with all its songs. Writes the link the phone uses (the emulator reaches this computer at 10.0.2.2) to pc/link.txt.
Usage: pc_server.py <music folder>"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))
import mfp_server as s  # noqa: E402

music = str(Path(sys.argv[1]).resolve())
s.settings.data["folders"] = [music]
s.index.scan([music])
s.collections.create("PC Party", sorted(s.index.tracks, key=lambda t: s.index.tracks[t]["title"]))
s.CONFIG.update(lan=True, token="ci-test-key-0123456789")
(ROOT / "pc" / "link.txt").write_text(f"http://10.0.2.2:{s.PORT}/?t={s.CONFIG['token']}")
print(f"{len(s.index.tracks)} songs, link written", flush=True)

# ---- A fake Spotify for the phone's Spotify account (SpotifyBot.kt in the test build): the sign-in page sets the
# session cookie, the web player pages send their access token and show a library and song rows, and the Web API
# answers for "Fake Mix" but refuses "Page Only" (the bot then reads that playlist from the page).
import html as _html  # noqa: E402
from flask import jsonify, make_response, redirect, request  # noqa: E402

SP = "/fake-spotify"
SP_TOKEN = "fake-user-token-42"
SP_SONGS = [(t["title"], t["artist"], t.get("album") or "", int(t.get("duration") or 120))
            for t in sorted(s.index.tracks.values(), key=lambda t: t["title"])] + [("Fake Unknown Song", "Nobody", "", 200)]
SP_LISTS = {"mix1": "Fake Mix", "page2": "Page Only"}


def _sp_page(body):
    return ("<!doctype html><meta name=viewport content='width=device-width'><title>Spotify</title>"
            "<div id='Desktop_LeftSidebar_Id'>" + "".join(
                f"<div role='row' aria-labelledby='listrow-title-spotify:playlist:{k}'><img src='https://i.scdn.co/image/{k}' alt=''><p>Playlist • Me</p><span id='listrow-title-spotify:playlist:{k}'>{v}</span></div>"
                for k, v in SP_LISTS.items()) + "</div><main>" + body + "</main>"
            f"<script>fetch('{SP}/pathfinder', {{headers: {{authorization: 'Bearer {SP_TOKEN}'}}}});</script>")


@s.app.route(SP + "/login")
def sp_login():
    r = make_response(redirect(SP + "/"))
    r.set_cookie("sp_dc", "fake-session", path="/")
    return r


@s.app.route(SP + "/")
@s.app.route(SP + "/collection/tracks")
@s.app.route(SP + "/playlist/<pid>")
def sp_web(pid=""):
    if pid not in ("", *SP_LISTS):
        return "Not found", 404
    rows = "".join(f"<div role='row' aria-rowindex='{i + 2}'><div data-testid='tracklist-row'>"
                   f"<a data-testid='internal-track-link' href='{SP}/track/{i}'>{_html.escape(t)}</a> "
                   f"<a href='{SP}/artist/{i}'>{_html.escape(a)}</a> <a href='{SP}/album/{i}'>{_html.escape(al)}</a> "
                   f"{d // 60}:{d % 60:02d}</div></div>" for i, (t, a, al, d) in enumerate(SP_SONGS))
    name = SP_LISTS.get(pid, "Liked Songs" if "collection" in request.path else "Home")
    return _sp_page(f"<h1>{name}</h1><p>{len(SP_SONGS)} songs</p><div style='height:300px;overflow-y:auto'>{rows}</div>" if pid or "collection" in request.path else "<h1>Home</h1>")


@s.app.route(SP + "/pathfinder")
def sp_pathfinder():
    return jsonify({})


def _sp_auth():
    return request.headers.get("Authorization") == "Bearer " + SP_TOKEN


def _sp_track(i, t):
    return {"type": "track", "name": t[0], "artists": [{"name": t[1]}], "album": {"name": t[2], "images": []}, "duration_ms": t[3] * 1000}


@s.app.route(SP + "/v1/me/playlists")
def sp_api_lists():
    if not _sp_auth():
        return jsonify({"error": {"status": 401, "message": "No token"}}), 401
    return jsonify({"items": [{"id": k, "name": v, "tracks": {"total": len(SP_SONGS)}, "images": []} for k, v in SP_LISTS.items()], "next": None})


@s.app.route(SP + "/v1/playlists/<pid>")
@s.app.route(SP + "/v1/playlists/<pid>/tracks")
def sp_api_list(pid):
    if not _sp_auth():
        return jsonify({"error": {"status": 401, "message": "No token"}}), 401
    if pid != "mix1":
        return jsonify({"error": {"status": 429, "message": "Too many requests"}}), 429
    if request.path.endswith("/tracks"):
        return jsonify({"items": [{"track": _sp_track(i, t)} for i, t in enumerate(SP_SONGS)], "next": None})
    return jsonify({"name": SP_LISTS[pid]})


from werkzeug.serving import make_server  # noqa: E402
make_server("0.0.0.0", s.PORT, s.app, threaded=True).serve_forever()
