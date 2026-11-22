"""Emulator test of the Android app. Installs the APK (a test build with WebView debugging on), puts one song
per format on the device, opens the app and drives its page through the WebView's DevTools socket: it plays
each song and checks that the position moves. Then it checks that Android's media session reports playback, that MusicFlow's search and download loaded inside
the app, imports a playlist from a computer's Tunedrift (PC_LINK) while a song plays, imports it again (nothing
is copied twice), and swipes the app away from the recent apps while a song plays (the music must go on).
Usage: smoke_test.py <apk> <out_dir> <songs_dir>
The songs dir holds one "Fmt <ext> - Tester.<ext>" file per format (made with ffmpeg by the workflow)."""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import websocket  # pip install websocket-client

APK, OUT, SONGS = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
PACKAGE = "com.musicflow.play"
FORMATS = ["mp3", "m4a", "flac", "opus", "ogg", "wav"]


def adb(*args, check=True, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stdout}{r.stderr}")
    return r.stdout


def screenshot(name):
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    (OUT / f"{name}.png").write_bytes(data)


def finish(message=None):
    screenshot("end")
    log = adb("logcat", "-d", "-v", "time", check=False)
    (OUT / "logcat.txt").write_text(log, errors="replace")
    (OUT / "media_session.txt").write_text(adb("shell", "dumpsys", "media_session", check=False))
    for line in [l for l in log.splitlines() if any(k in l for k in ("MFPConsole", "MFP_AUDIO", "AndroidRuntime", "python.std", "MusicFlowPlay", "chromium"))][-120:]:
        print(line)
    if message:
        raise SystemExit("FAIL: " + message)
    print("PASS")


def wait_for(what, seconds, probe, every=2):
    end = time.time() + seconds
    while time.time() < end:
        found = probe()
        if found:
            return found
        time.sleep(every)
    finish(f"{what} (waited {seconds} s)")


def close_system_dialogs():
    xml_path = "/sdcard/ui.xml"
    adb("shell", "uiautomator", "dump", xml_path, check=False)
    xml = adb("shell", "cat", xml_path, check=False)
    for label in ("Wait", "Close app"):
        m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % label, xml)
        if m and "isn't responding" in xml:
            x1, y1, x2, y2 = map(int, m.groups())
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), check=False)
            return True
    return False


adb("install", "-r", APK, timeout=300)
# Android 12: no READ_MEDIA_AUDIO / POST_NOTIFICATIONS yet (13+); storage permission instead.
for permission in ("READ_MEDIA_AUDIO", "POST_NOTIFICATIONS", "READ_EXTERNAL_STORAGE"):
    adb("shell", "pm", "grant", PACKAGE, f"android.permission.{permission}", check=False)
# Access to all files: the songs of an import are saved in a root folder of the person's choice.
adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow", check=False)
adb("shell", "mkdir", "-p", "/sdcard/Music")
for ext in FORMATS:
    f = SONGS / f"Fmt {ext} - Tester.{ext}"
    adb("push", str(f), f"/sdcard/Music/{f.name}")
# Let Android's media store index the files (an app with READ_MEDIA_AUDIO only sees indexed music).
adb("shell", "content", "call", "--method", "scan_volume", "--uri", "content://media", "--arg", "external_primary", check=False, timeout=180)
adb("logcat", "-c", check=False)
time.sleep(5)
close_system_dialogs()
# The test build talks to a fake Spotify on the test computer (ci/pc_server.py) instead of Spotify.
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", "--es", "test_spotify_base", "http://10.0.2.2:5091/fake-spotify")


def devtools_socket():
    pid = adb("shell", "pidof", PACKAGE, check=False).strip()
    if not pid:
        return None
    unix = adb("shell", "cat", "/proc/net/unix", check=False)
    name = f"webview_devtools_remote_{pid}"
    return name if name in unix else None


sock = wait_for("the app's WebView did not open a DevTools socket", 180, devtools_socket)
adb("forward", "tcp:9222", f"localabstract:{sock}")


def page_target():
    try:
        with urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5) as r:
            pages = json.loads(r.read())
    except Exception:
        return None
    return next((p for p in pages if p.get("url", "").startswith("http://127.0.0.1:5091")), None)


target = wait_for("the player page did not load in the WebView", 240, page_target, every=3)
ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=30, suppress_origin=True)
msg_id = 0


def js(expression):
    global msg_id
    msg_id += 1
    ws.send(json.dumps({"id": msg_id, "method": "Runtime.evaluate",
                        "params": {"expression": expression, "returnByValue": True, "awaitPromise": True}}))
    while True:
        reply = json.loads(ws.recv())
        if reply.get("id") == msg_id:
            result = reply.get("result", {})
            if "exceptionDetails" in result:
                return {"exception": result["exceptionDetails"].get("text")}
            return result.get("result", {}).get("value")


checks = 0


def library_ready():
    global checks
    checks += 1
    close_system_dialogs()
    info = js("""(async () => { let st = null; try { st = await api('state'); } catch (e) { st = String(e); }
      return {loaded: !!(typeof L !== "undefined" && L.loaded), error: typeof L !== "undefined" ? L.error : 'no L', scan: typeof L !== "undefined" ? L.scan : null,
              titles: typeof L !== "undefined" && L.loaded ? L.list.map(t => t.title) : [], state: st}; })()""") or {}
    print("library:", json.dumps(info)[:600])
    titles = info.get("titles") or []
    if checks % 6 == 0:  # every 30 s, ask for a new scan
        js("window.MFP_rescan && MFP_rescan()")
    return all(f"Fmt {ext}" in titles for ext in FORMATS)


wait_for("the songs are not all listed in the library", 240, library_ready, every=5)
screenshot("library")

# Screen edges: the page draws behind the system bars and knows their size; the Folders view is shown first.
edges = js("""({top: getComputedStyle(document.documentElement).getPropertyValue('--sa-t').trim(),
  folders: !!document.querySelector('#main .crumbs'), y: Math.round(document.querySelector('#topbar').getBoundingClientRect().top)})""") or {}
print("edges:", edges)
EDGE_FAIL = None
if not (edges.get("folders") and edges.get("top", "").endswith("px") and float(edges["top"][:-2]) > 0 and edges.get("y") == 0):
    EDGE_FAIL = f"FAILED {edges}"

STATE = """(() => { const a = document.querySelector('#audio'); const b = a.buffered;
  return {t: a.currentTime, paused: a.paused, ready: a.readyState, net: a.networkState,
          err: a.error ? a.error.code + ' ' + (a.error.message || '') : '',
          buffered: b.length ? b.end(b.length - 1) : 0, dur: a.duration, src: (a.currentSrc || '').split('?')[0]}; })()"""

results = {}
if EDGE_FAIL:
    results["edges and folders"] = EDGE_FAIL
for ext in FORMATS:
    tid = js(f"L.list.find(t => t.title === 'Fmt {ext}').id")
    js(f"Player.playList(['{tid}'], 0, false)")
    state = {}
    end = time.time() + 30
    while time.time() < end:
        time.sleep(1.5)
        state = js(STATE) or {}
        if state.get("err") or (state.get("t", 0) > 2 and not state.get("paused")):
            break
    ok = state.get("t", 0) > 2 and not state.get("err")
    results[ext] = "ok" if ok else f"FAILED {state}"
    print(f"{ext} state: {state}")
    print(f"{ext}: {results[ext]}")
screenshot("formats")

# The fallback that plays a song from memory (used when a stream does not start) must work too.
tid = js("L.list.find(t => t.title === 'Fmt flac').id")
js(f"Player.playList(['{tid}'], 0, false)")
time.sleep(1)
js(f"Player.blobTried = null; Player.playFromMemory('{tid}', 'test')")
state = {}
for _ in range(20):
    time.sleep(1.5)
    state = js(STATE) or {}
    if state.get("src", "").startswith("blob:") and state.get("t", 0) > 2:
        break
print("from memory:", state)
if not (state.get("src", "").startswith("blob:") and state.get("t", 0) > 2 and not state.get("err")):
    results["flac from memory"] = f"FAILED {state}"

# Sound: equalizer, same volume and speed go through Web Audio; the song must still play, faster.
js("FX.s.eq = true; FX.s.level = true; FX.s.bands = EQ_PRESETS.bass[1].slice(); FX.s.speed = 1.25; FX.applyRate(); FX.applyAudio();"
   " Player.playList([L.list.find(t => t.title === 'Fmt mp3').id], 0, false); true")
time.sleep(2)
fx0 = js(STATE) or {}
time.sleep(4)
fx1 = js(STATE) or {}
fx = js("({ctx: FX.ctx ? FX.ctx.state : 'none', rate: audio.playbackRate})") or {}
print("sound:", fx, fx0.get("t"), fx1.get("t"))
moved = fx1.get("t", 0) - fx0.get("t", 0)
results["equalizer and speed"] = "ok" if fx.get("ctx") == "running" and fx.get("rate") == 1.25 and moved > 3 and not fx1.get("paused") else f"FAILED {fx} {fx0} {fx1}"
js("FX.s.eq = false; FX.s.level = false; FX.s.speed = 1; FX.save(); FX.applyRate(); FX.applyAudio(); true")

# Home screen widgets: the five kinds are offered, and a shortcut (shuffle everything) plays.
providers = adb("shell", "dumpsys", "appwidget", check=False)
kinds = sorted(set(re.findall(PACKAGE.replace(".", r"\.") + r"/\.?[\w.]*(PlayerWidget|WidgetMini|WidgetCard|WidgetBig|WidgetShortcuts)", providers)))
print("widgets:", kinds)
results["widgets offered"] = "ok" if len(kinds) == 5 else f"FAILED {kinds}"
js("audio.pause(); MFP_widget('shuffleall'); true")
time.sleep(3)
w = js("({shuffle: Player.shuffle, n: Player.queue.length, paused: audio.paused})") or {}
print("widget shortcut:", w)
results["widget shortcut"] = "ok" if w.get("shuffle") and w.get("n", 0) >= 3 and not w.get("paused") else f"FAILED {w}"
js("Player.setShuffle(false); true")

js("Player.playList([L.list.find(t => t.title === 'Fmt mp3').id], 0, false)")


def session_playing():
    dump = adb("shell", "dumpsys", "media_session", check=False)
    text = "\n".join(block for block in dump.split("\n\n") if PACKAGE in block)
    return "Fmt mp3" in text and re.search(r"state=(PlaybackState \{state=)?(3|PLAYING)", text)


session = wait_for("Android's media session does not report the song as playing", 60, session_playing)

# MusicFlow (search and download) is part of the app now: no module to install, it loads by itself.
mf = wait_for("MusicFlow's search and download did not load in the app", 240,
              lambda: (js("api('state').then(s => s.musicflow)") or {}).get("state") in ("ready", "error"), every=5)
mf = js("api('state').then(s => s.musicflow)") or {}
print("musicflow:", mf)
if mf.get("state") != "ready":
    results["musicflow in the app"] = f"FAILED {mf}"

# Import tab: downloads, sources, root folder.
js("location.hash = 'download'")
wait_for("the Import tab did not open", 60, lambda: js("!!document.querySelector('#src-box') && !!document.querySelector('#dl-box .actions, #dl-box p')"), every=2)
screenshot("import")

# Import a playlist of the "computer" while a song plays: the songs are copied into the root folder, the music
# goes on, and the app does not close.
pid_before = adb("shell", "pidof", PACKAGE, check=False).strip()
js("Player.playList([L.list.find(t => t.title === 'Fmt mp3').id], 0, false)")
time.sleep(3)
link = os.environ.get("PC_LINK", "")
print("computer:", link)
lists = js(f"api('import/playlists', {{kind: 'server', address: {json.dumps(link)}}}).catch(e => ({{error: e.message}}))") or {}
print("playlists:", lists)
party = next((p for p in lists.get("playlists") or [] if p.get("name") == "PC Party"), None)
if not party:
    results["import from the computer"] = f"FAILED {lists}"
else:
    js("api('dl/settings', {root: '/storage/emulated/0/Music/Imported'})")
    started = js(f"api('import/start', {{source: {{kind: 'server', base: {json.dumps(lists['base'])}, id: {json.dumps(party['id'])}}}}}).catch(e => ({{error: e.message}}))") or {}
    print("import:", started)
    js("location.hash = 'download'; render()")

    def copied():
        d = js("api('dl')") or {}
        c = d.get("counts") or {}
        print("queue:", c)
        return not c.get("queued") and not c.get("working") and (c.get("done", 0) + c.get("error", 0) + c.get("have", 0)) >= 4 and c
    counts = wait_for("the import did not finish", 240, copied, every=4)
    screenshot("import-done")
    a = js(STATE) or {}
    pid_after = adb("shell", "pidof", PACKAGE, check=False).strip()
    print("after import:", counts, a, pid_before, pid_after)
    if counts.get("done", 0) < 4:
        results["import from the computer"] = f"FAILED {counts}"
    if pid_after != pid_before or a.get("paused") or a.get("err"):
        results["playing while importing"] = f"FAILED pid {pid_before} -> {pid_after}, {a}"
    # Again: everything is on the device now, nothing is copied a second time.
    again = js(f"api('import/start', {{source: {{kind: 'server', base: {json.dumps(lists['base'])}, id: {json.dumps(party['id'])}}}}}).then(r => r.import.queued)") or 0
    time.sleep(8)
    d = js("api('dl')") or {}
    linked = [i for i in d.get("items") or [] if i.get("state") == "have"]
    print("second import:", again, "linked:", len(linked))
    if len(linked) < 4:
        results["no download twice"] = f"FAILED {len(linked)} linked, {d.get('counts')}"
    pl = js("api('collections').then(c => c.playlists.map(p => [p.name, p.tracks.length]))")
    print("playlists here:", pl)
    if not any(n == "PC Party" and k == 4 for n, k in pl or []):
        results["imported playlist"] = f"FAILED {pl}"

# Spotify with the person's own account, no key: sign in on Spotify's page in the app, then a hidden web player
# gives the playlists (Web API with the session's token, or read from the page when the API refuses).
js("location.hash = 'download'; IMP.src = 'spotify'; IMP.drawSource()")
time.sleep(2)
sp = js("api('import/state').then(s => s.spotify)") or {}
print("spotify before:", sp)
js("native('spotifyLogin')")
time.sleep(1)
screenshot("spotify-login")
sp = wait_for("Spotify sign-in did not finish", 60, lambda: (js("api('import/state').then(s => s.spotify)") or {}).get("connected") and True, every=2)
results["spotify sign-in"] = "ok" if sp else "FAILED"
sp_lists = js("api('import/playlists', {kind: 'spotify'}).catch(e => ({error: e.message}))") or {}
print("spotify playlists:", sp_lists)
names = [p.get("name") for p in sp_lists.get("playlists") or []]
results["spotify playlists"] = "ok" if names == ["Liked Songs", "Fake Mix", "Page Only"] else f"FAILED {sp_lists}"
for pid, label in (("mix1", "spotify songs (api)"), ("page2", "spotify songs (page)")):
    pv = js(f"api('import/preview', {{source: {{kind: 'spotify', id: '{pid}'}}}}).catch(e => ({{error: e.message}}))") or {}
    print(label, pv.get("name"), len(pv.get("tracks") or []), "on the device:", pv.get("have"), pv.get("error", ""))
    good = len(pv.get("tracks") or []) == 5 and pv.get("have", 0) >= 4 and any(t.get("title") == "Fake Unknown Song" for t in pv["tracks"])
    results[label] = "ok" if good else f"FAILED {str(pv)[:400]}"
# The songs read once are kept: opening the playlist again is instant (the Spotify tab).
t0 = time.time()
kept = js("api('import/spotify?pid=page2').catch(e => ({error: e.message}))") or {}
took = time.time() - t0
print("spotify kept songs:", len(kept.get("tracks") or []), kept.get("complete"), f"{took:.1f} s")
results["spotify songs kept"] = "ok" if kept.get("complete") and len(kept.get("tracks") or []) == 5 and took < 3 else f"FAILED {str(kept)[:300]}"
js("IMP.drawSource()")
time.sleep(4)
screenshot("spotify-connected")
js("native('spotifyLogout')")
time.sleep(2)
out = js("api('import/state').then(s => s.spotify)") or {}
results["spotify sign-out"] = "ok" if out.get("connected") is False else f"FAILED {out}"

# Search while typing (online): one search once typing stops, not one per letter, and the page never stalls.
js("location.hash = ''; window.__srch = 0; { const s0 = MF.search; MF.search = q => { __srch++; return s0(q); }; }"
   " window.__lag = 0; { let t = performance.now(); window.__lagT = setInterval(() => { const n = performance.now(); __lag = Math.max(__lag, n - t - 100); t = n; }, 100); }"
   " SRCH.scope = 'online'; true")
for i in range(1, len("Internet") + 1):
    js(f"$('#q').value = 'Internet'.slice(0, {i}); $('#q').dispatchEvent(new Event('input')); true")
    time.sleep(0.15)
done = wait_for("the online search did not finish", 120,
                lambda: js("SRCH.online.q === 'Internet' && !SRCH.online.loading ? {n: __srch, lag: Math.round(__lag), found: (SRCH.online.results || []).length, err: SRCH.online.error || ''} : null"), every=2)
screenshot("search-online")
print("search while typing:", done)
results["search while typing"] = "ok" if done["n"] <= 2 and done["lag"] < 1500 else f"FAILED {done}"
js("clearInterval(__lagT); $('#q').value = ''; $('#q').dispatchEvent(new Event('input')); SRCH.scope = 'all'; true")

# Update: the app downloads the latest release by itself, with progress; a second tap does not start it again.
# The release is older than this test build, so after the whole download the app must refuse it ("not newer").
js("window.__upd = []; { const p0 = window.MFP_updateProgress, e0 = window.MFP_updateError;"
   " window.MFP_updateProgress = p => { __upd.push(p); p0(p); }; window.MFP_updateError = m => { __upd.push('error: ' + m); e0(m); }; } true")
latest = js("api('latest-version').catch(e => ({error: e.message}))") or {}
apk_url = next((a["url"] for n in ("Tunedrift.apk", "MusicFlowPlay.apk") for a in latest.get("assets") or [] if a.get("name") == n),
               "https://github.com/captentv1/tunedrift/releases/latest/download/Tunedrift.apk")
print("update from:", apk_url)
js(f"native('installUpdate', {json.dumps(apk_url)})")
time.sleep(3)
js(f"native('installUpdate', {json.dumps(apk_url)})")  # the second tap of the bug report
upd = wait_for("the update download did not finish", 300,
               lambda: (lambda u: u if u and any(isinstance(x, str) or x == 100 for x in u) else None)(js("window.__upd")), every=3)
print("update events:", upd[:6], "...", upd[-4:])
ends = [x for x in upd if isinstance(x, str) or x == 100]
progressed = any(isinstance(x, int) and 0 <= x < 100 for x in upd)
results["update download"] = "ok" if progressed and ends and (ends[0] == 100 or "not newer" in str(ends[0])) else f"FAILED {upd[-6:]}"

# The light glass themes, kept for the rest of the test: leaving and re-entering the app must leave it usable.
js("applyTheme('glass-light')")
# Swiped away from the recent apps while playing: the music goes on (as in Samsung Music).
js("Player.playList([L.list.find(t => t.title === 'Fmt mp3').id], 0, false)")
time.sleep(3)
size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size", check=False))
w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 1920)
def has_task():
    return f"{PACKAGE}/.MainActivity" in adb("shell", "dumpsys", "activity", "recents", check=False)


adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH", check=False)
time.sleep(4)
screenshot("recents")
# Swipe the app's card up (its bounds from the screen's layout, else the middle of the screen).
adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
ui = adb("shell", "cat", "/sdcard/ui.xml", check=False)
card = re.search(r'resource-id="[^"]*(snapshot|task_thumbnail|thumbnail)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
x, y = ((int(card.group(2)) + int(card.group(4))) // 2, (int(card.group(3)) + int(card.group(5))) // 2) if card else (w // 2, h // 2)
adb("shell", "input", "swipe", str(x), str(y), str(x), str(h // 20), "400", check=False)
time.sleep(3)
how = "recent-apps swipe"
if has_task():
    # Some emulator launchers ignore the swipe: the test build removes its task the same way.
    how = "task removed by the test build"
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", "--ez", "test_remove_task", "true", check=False)
    time.sleep(4)
adb("shell", "input", "keyevent", "KEYCODE_HOME", check=False)
time.sleep(6)
screenshot("after-swipe")
still_task = has_task()
print("task removed by:", how)
pid_now = adb("shell", "pidof", PACKAGE, check=False).strip()
t1 = js(STATE) if pid_now else {}
time.sleep(4)
t2 = js(STATE) if pid_now else {}
print("after swipe: task", still_task, "pid", pid_now, t1, t2)
if not pid_now or not t2 or t2.get("paused") or not t2.get("t", 0) > (t1 or {}).get("t", 0):
    results["music after swiping the app away"] = f"FAILED pid={pid_now} task={still_task} {t1} {t2}"
if still_task:
    results["task removed"] = "FAILED the app's task is still in the recent apps"



def webview_box():
    """Screen bounds of the player's WebView (the biggest one on screen)."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    ui = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    boxes = [tuple(map(int, m.groups())) for m in re.finditer(r'class="android.webkit.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)]
    return max(boxes, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])) if boxes else None


def answers():
    """The page answers within 5 seconds (it is not frozen)."""
    t0 = time.time()
    try:
        ok = js("new Promise(r => requestAnimationFrame(() => r(1)))") == 1
    except Exception as exc:
        print("page did not answer:", exc)
        return False
    return ok and time.time() - t0 < 5


def tap_settings(label):
    """Taps the Settings button with a real touch: the settings page must open."""
    js("location.hash = 'folders'")
    time.sleep(1)
    box = webview_box()
    r = js("(() => { const b = document.querySelector('#btn-settings').getBoundingClientRect(); return {x: b.left + b.width / 2, y: b.top + b.height / 2, dpr: devicePixelRatio}; })()") or {}
    if not box or not r.get("dpr"):
        return f"no WebView or button {box} {r}"
    x, y = box[0] + int(r["x"] * r["dpr"]), box[1] + int(r["y"] * r["dpr"])
    adb("shell", "input", "tap", str(x), str(y), check=False)
    time.sleep(2)
    screenshot(label)
    name = js("route().name")
    theme = js("document.documentElement.getAttribute('data-theme')")
    return "ok" if name == "settings" and theme == "glass-light" else f"FAILED route={name} theme={theme} tap=({x},{y})"


# Leaving and re-entering the app (Light glass theme): after the swipe (page kept, music playing), after Home,
# and after a cold start, the page answers and a real tap opens the settings.
for label, leave in (("re-enter after swipe", None), ("re-enter after home", "home"), ("re-enter after cold start", "cold")):
    if leave == "home":
        adb("shell", "input", "keyevent", "KEYCODE_HOME", check=False)
        time.sleep(4)
    elif leave == "cold":
        js("Player.toggle && audio.pause()")
        adb("shell", "am", "force-stop", PACKAGE, check=False)
        time.sleep(2)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", check=False)
    time.sleep(6)
    if leave == "cold":
        try:
            ws.close()
        except Exception:
            pass
        sock = wait_for("the app's WebView did not come back after a cold start", 120, devtools_socket)
        adb("forward", "tcp:9222", f"localabstract:{sock}")
        target = wait_for("the player page did not load after a cold start", 180, page_target, every=3)
        ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=30, suppress_origin=True)
        wait_for("the library did not load after a cold start", 120, lambda: js("L.loaded === true"), every=2)
    screenshot(label.replace(" ", "-"))
    if not answers():
        results[label] = "FAILED the page is frozen"
        continue
    results[label] = tap_settings(label.replace(" ", "-") + "-settings")
    print(label, results[label])

# The widget's play button with the app closed (ended by Android): the music starts in the background, the app's
# window does not open, and the notification is there.
js("audio.pause()")
time.sleep(1)
adb("shell", "am", "force-stop", PACKAGE, check=False)
time.sleep(2)
adb("shell", "am", "start-foreground-service", "-n", f"{PACKAGE}/.PlaybackService", "-a", "com.musicflow.play.COMMAND",
    "--es", "command", "play", check=False)
try:
    ws.close()
except Exception:
    pass
try:
    sock = wait_for("no page after the widget's play with the app closed", 120, devtools_socket)
    adb("forward", "tcp:9222", f"localabstract:{sock}")
    target = wait_for("the page did not load in the background", 180, page_target, every=3)
    ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=30, suppress_origin=True)
    wait_for("the music did not start from the widget", 120, lambda: js("typeof audio !== 'undefined' && !audio.paused && audio.currentTime > 1"), every=2)
    resumed = adb("shell", "dumpsys", "activity", "activities", check=False)
    window_open = any(f"{PACKAGE}/.MainActivity" in l for l in resumed.splitlines() if "mResumedActivity" in l or "ResumedActivity:" in l)
    notif = f"pkg={PACKAGE}" in adb("shell", "dumpsys", "notification", "--noredact", check=False)
    screenshot("widget-play-app-closed")
    results["widget play with the app closed"] = "ok" if not window_open and notif else f"FAILED window_open={window_open} notification={notif}"
except Exception as exc:
    results["widget play with the app closed"] = f"FAILED {exc}"
print("widget play with the app closed:", results["widget play with the app closed"])

bad = {k: v for k, v in results.items() if v != "ok"}
finish(f"failed: {bad}" if bad else None)
