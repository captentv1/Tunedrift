"""Runs MusicFlow (search and download) next to the player, on its own port, so the player can show it
in a frame. MusicFlow's code is used as it is (git submodule); this module only allows the player to frame
it and adds a few styles when it is shown inside the player."""
from __future__ import annotations

import sys
import threading

from flask import request

from paths import ROOT

PORT = 5090
URL = f"http://127.0.0.1:{PORT}/?embed=1"
PLAYER_ORIGINS = "http://127.0.0.1:5091 http://localhost:5091"

# Inside the player: the theme follows the player, MusicFlow's bottom tabs move to the top on a phone
# (the player keeps its own tabs and mini player at the bottom), and the player is told when a download ends.
_HEAD = """<script>(function(){document.documentElement.classList.add('mfp-embed');
try{var th=new URLSearchParams(location.search).get('theme');if(th!==null)localStorage.setItem('musicflow.theme',th);}catch(e){}
addEventListener('message',function(e){if(!/^http:\\/\\/(127\\.0\\.0\\.1|localhost):5091$/.test(e.origin))return;
 var d=e.data||{};if(typeof d.mfpTheme==='string'&&window.applyTheme)applyTheme(d.mfpTheme);});
var done=0;setInterval(function(){var n=0;try{n=TASKS.reduce(function(a,t){return a+(t.successes||0);},0);}catch(e){}
 if(n!==done){done=n;try{parent.postMessage({mfDownloaded:n},'*');}catch(e){}}},4000);})();</script>
<style>
@media (max-width:760px){
 html.mfp-embed #bottomnav{top:calc(54px + var(--top-inset,0px))!important;bottom:auto!important;left:0!important;right:0!important;
   height:56px!important;border-radius:0!important;padding:0 6px!important;border-width:0 0 1px 0!important}
 html.mfp-embed #shell{margin-top:calc(112px + var(--top-inset,0px))!important}
 html.mfp-embed #shell, html.mfp-embed .view{padding-bottom:16px!important}
}
</style>"""


def _paths() -> None:
    for d in (ROOT / "musicflow" / "desktop", ROOT / "musicflow" / "core"):
        if d.is_dir() and str(d) not in sys.path:
            sys.path.insert(0, str(d))


def load():
    """Imports MusicFlow's desktop server and lets the player frame it."""
    _paths()
    import app as mf  # MusicFlow's desktop/app.py

    @mf.app.after_request
    def _for_player(resp):
        # Security: only the player's own page may show MusicFlow in a frame (overrides X-Frame-Options: DENY).
        resp.headers["Content-Security-Policy"] = "frame-ancestors " + PLAYER_ORIGINS
        if request.path == "/" and request.args.get("embed") and resp.mimetype == "text/html":
            resp.direct_passthrough = False
            resp.set_data(resp.get_data(as_text=True).replace("<head>", "<head>" + _HEAD, 1))
        return resp

    return mf


def start():
    """Starts MusicFlow in a background thread. Returns MusicFlow's module, or None if it can't run."""
    try:
        mf = load()
        from werkzeug.serving import make_server
        server = make_server("127.0.0.1", PORT, mf.app, threaded=True)
    except Exception as exc:  # missing packages, or MusicFlow already open on its port
        print(f"MusicFlow (search and download) is not available: {exc}", file=sys.stderr)
        return None
    server.socket.set_inheritable(False)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    prewarm = getattr(mf, "_prewarm_search", None)
    if prewarm:
        threading.Thread(target=prewarm, daemon=True).start()
    return mf
