"""Runs MusicFlow (search and download) next to the player, on its own port, so the player can show it
in a frame. MusicFlow's code is used as it is (git submodule); this module only allows the player to frame
it and adds a few styles when it is shown inside the player."""
from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import urllib.request
import zipfile
from pathlib import Path

from flask import request

from paths import ROOT, data_dir

PORT = 5090
URL = f"http://127.0.0.1:{PORT}/?embed=1"
PLAYER_ORIGINS = "http://127.0.0.1:5091 http://localhost:5091"

# Inside the player: the theme follows the player, MusicFlow's bottom tabs move to the top on a phone
# (the player keeps its own tabs and mini player at the bottom), and the player is told when a download ends.
_HEAD = """<script>(function(){document.documentElement.classList.add('mfp-embed');
try{var th=new URLSearchParams(location.search).get('theme');if(th!==null)localStorage.setItem('musicflow.theme',th);}catch(e){}
// Android app: the token comes in the address fragment (never sent to the server), then is removed from the address.
var m=/[#&]mft=([\\w-]+)/.exec(location.hash);if(m){window.MF_TOKEN=m[1];history.replaceState(null,'',location.pathname+location.search);}
var CALLS=['MF_tic','MF_share','MF_voice','onFolderChosen','onScrapeProgress','onScrapeDiag','onScrapeComplete','MF_updateProgress','MF_updateError'];
addEventListener('message',function(e){if(!/^http:\\/\\/(127\\.0\\.0\\.1|localhost):5091$/.test(e.origin))return;
 var d=e.data||{};if(typeof d.mfpTheme==='string'&&window.applyTheme)applyTheme(d.mfpTheme);
 if(CALLS.indexOf(d.mfCall)>=0&&typeof window[d.mfCall]==='function')window[d.mfCall].apply(null,d.args||[]);});
addEventListener('load',function(){try{parent.postMessage({mfReady:true},'*');}catch(e){}});
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


# ---- The Download tab as a module: the exe and the Android app don't include MusicFlow; the person installs
# it with a button, from this version's GitHub release (MusicFlowModule-windows.zip / -android.zip). ----

MODULE = {"state": "missing", "pct": 0, "error": "", "version": ""}
STARTER = None  # starts MusicFlow once its module is there (set by the PC or Android start-up)


def _android() -> bool:
    return hasattr(sys, "getandroidapilevel")


def bundled() -> bool:
    """Run from the source folder: MusicFlow is the git submodule, nothing to install."""
    return not getattr(sys, "frozen", False) and not _android() and (ROOT / "musicflow" / "desktop" / "app.py").is_file()


def module_dir() -> Path:
    return data_dir() / "musicflow-module"


def module_version() -> str:
    try:
        return str(json.loads((module_dir() / "module.json").read_text(encoding="utf-8"))["version"])
    except Exception:
        return ""


def module_url(releases: str, version: str) -> str:
    name = "MusicFlowModule-android.zip" if _android() else "MusicFlowModule-windows.zip"
    return f"{releases}/download/v{version}/{name}"


def _use_module() -> None:
    d = str(module_dir())
    if d not in sys.path:
        sys.path.insert(0, d)


def _ssl_context():
    import ssl
    try:
        import truststore
        return truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    except Exception:
        pass
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def prepare(starter, version: str, releases: str) -> None:
    """At start: runs MusicFlow if its module (for this version of the player) is installed."""
    global STARTER
    STARTER = starter
    if bundled():
        MODULE.update(state="bundled")
        starter()
        return
    have = module_version()
    if have == version:
        MODULE.update(state="installed", version=have)
        _use_module()
        starter()
    elif have:  # the player was updated: get the matching module
        install(module_url(releases, version))
    elif os.environ.get("MFP_MODULE_URL"):  # tests
        install(os.environ["MFP_MODULE_URL"])


def install(url: str) -> None:
    """Downloads and unpacks the module in the background, then starts MusicFlow."""
    if MODULE["state"] == "downloading":
        return
    MODULE.update(state="downloading", pct=0, error="")

    def run():
        target = module_dir()
        tmp, new = target.with_name("musicflow-module.zip"), target.with_name("musicflow-module.new")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "MusicFlowPlay"})
            with urllib.request.urlopen(req, timeout=60, context=_ssl_context()) as r, open(tmp, "wb") as f:
                total, done = int(r.headers.get("Content-Length") or 0), 0
                while chunk := r.read(1 << 16):
                    f.write(chunk)
                    done += len(chunk)
                    MODULE["pct"] = int(done * 100 / total) if total else -1
            shutil.rmtree(new, ignore_errors=True)
            with zipfile.ZipFile(tmp) as z:
                root = new.resolve()
                # Security: every file stays inside the module's folder.
                if any(not (new / n).resolve().is_relative_to(root) for n in z.namelist()):
                    raise ValueError("unexpected file in the download")
                z.extractall(new)
            if not (new / "module.json").is_file():
                raise ValueError("this is not MusicFlow's module")
            shutil.rmtree(target, ignore_errors=True)
            new.rename(target)
            MODULE.update(state="installed", version=module_version(), pct=100)
            _use_module()
            if STARTER:
                STARTER()
        except Exception as exc:
            MODULE.update(state="error", error=f"{type(exc).__name__}: {exc}")
        finally:
            try:
                tmp.unlink()
            except OSError:
                pass

    threading.Thread(target=run, daemon=True).start()


def load():
    """Imports MusicFlow's desktop server and lets the player frame it."""
    _paths()
    import app as mf  # MusicFlow's desktop/app.py
    for_player(mf.app)
    return mf


def for_player(flask_app) -> None:
    """Lets the player show this MusicFlow server in a frame (PC: desktop/app.py, Android: mobile_app.py)."""
    @flask_app.after_request
    def _for_player(resp):
        # Security: only the player's own page may show MusicFlow in a frame (overrides X-Frame-Options: DENY).
        resp.headers["Content-Security-Policy"] = "frame-ancestors " + PLAYER_ORIGINS
        if request.path == "/" and request.args.get("embed") and resp.mimetype == "text/html":
            resp.direct_passthrough = False
            resp.set_data(resp.get_data(as_text=True).replace("<head>", "<head>" + _HEAD, 1))
        return resp


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
