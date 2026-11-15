"""Emulator test of the Android app. Installs the APK (a test build with WebView debugging on), puts one song
per format on the device, opens the app and drives its page through the WebView's DevTools socket: it plays
each song and checks that the position moves. Then it checks that Android's media session reports playback.
Usage: smoke_test.py <apk> <out_dir> <songs_dir>
The songs dir holds one "Fmt <ext> - Tester.<ext>" file per format (made with ffmpeg by the workflow)."""
import json
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
    for line in [l for l in log.splitlines() if "MFPConsole" in l or "MFP_AUDIO" in l or "AndroidRuntime: FATAL" in l][-80:]:
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
for permission in ("READ_MEDIA_AUDIO", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PACKAGE, f"android.permission.{permission}")
adb("shell", "mkdir", "-p", "/sdcard/Music")
for ext in FORMATS:
    f = SONGS / f"Fmt {ext} - Tester.{ext}"
    adb("push", str(f), f"/sdcard/Music/{f.name}")
# Let Android's media store index the files (an app with READ_MEDIA_AUDIO only sees indexed music).
adb("shell", "content", "call", "--method", "scan_volume", "--uri", "content://media", "--arg", "external_primary", check=False, timeout=180)
adb("logcat", "-c", check=False)
time.sleep(5)
close_system_dialogs()
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")


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

STATE = """(() => { const a = document.querySelector('#audio'); const b = a.buffered;
  return {t: a.currentTime, paused: a.paused, ready: a.readyState, net: a.networkState,
          err: a.error ? a.error.code + ' ' + (a.error.message || '') : '',
          buffered: b.length ? b.end(b.length - 1) : 0, dur: a.duration, src: (a.currentSrc || '').split('?')[0]}; })()"""

results = {}
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

js("Player.playList([L.list.find(t => t.title === 'Fmt mp3').id], 0, false)")


def session_playing():
    dump = adb("shell", "dumpsys", "media_session", check=False)
    text = "\n".join(block for block in dump.split("\n\n") if PACKAGE in block)
    return "Fmt mp3" in text and re.search(r"state=(PlaybackState \{state=)?(3|PLAYING)", text)


session = wait_for("Android's media session does not report the song as playing", 60, session_playing)
bad = {k: v for k, v in results.items() if v != "ok"}
finish(f"these formats do not play: {bad}" if bad else None)
