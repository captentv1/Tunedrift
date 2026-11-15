"""Installs the APK on the running emulator, puts one song on it, opens the app, taps the song and
checks that Android's media session reports it as playing. Usage: smoke_test.py <apk> <out_dir>"""
import math
import re
import struct
import subprocess
import sys
import time
import urllib.request
import wave
from pathlib import Path

APK, OUT = sys.argv[1], Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
PACKAGE = "com.musicflow.play"
SONG = "Smoke Test"


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
    (OUT / "logcat.txt").write_text(adb("logcat", "-d", "-v", "time", check=False), errors="replace")
    (OUT / "media_session.txt").write_text(adb("shell", "dumpsys", "media_session", check=False))
    if message:
        log = (OUT / "logcat.txt").read_text(errors="replace")
        for line in [l for l in log.splitlines() if "AndroidRuntime" in l or "python.stderr" in l or "chromium" in l][-60:]:
            print(line)
        raise SystemExit("FAIL: " + message)
    print("PASS: the app starts, lists the songs, plays every format and reports playback to Android")


def ui_nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    nodes = []
    for m in re.finditer(r"<node [^>]*>", xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(0)))
        label = attrs.get("text") or attrs.get("content-desc") or ""
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if label and b:
            x1, y1, x2, y2 = map(int, b.groups())
            nodes.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return nodes


def wait_for(what, seconds, probe):
    end = time.time() + seconds
    while time.time() < end:
        found = probe()
        if found:
            return found
        time.sleep(3)
    finish(f"{what} (waited {seconds} s)")


FORMATS = {"mp3": ["-c:a", "libmp3lame", "-b:a", "320k"], "m4a": ["-c:a", "aac", "-b:a", "256k"],
           "flac": ["-c:a", "flac"], "opus": ["-c:a", "libopus"], "ogg": ["-c:a", "libvorbis"], "wav": ["-c:a", "pcm_s16le"]}
songs = []
for ext, codec in FORMATS.items():
    f = OUT / f"Fmt {ext} - Tester.{ext}"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "anoisesrc=d=90:c=pink:a=0.3", "-ac", "2",
                    *codec, str(f)], check=True)
    songs.append(f)
song = OUT / f"{SONG} - CI Artist.wav"
song = OUT / f"{SONG} - CI Artist.wav"
with wave.open(str(song), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(22050)
    w.writeframes(b"".join(struct.pack("<h", int(9000 * math.sin(i * 2 * math.pi * 440 / 22050))) for i in range(22050 * 30)))

adb("install", "-r", APK, timeout=300)
for permission in ("READ_MEDIA_AUDIO", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PACKAGE, f"android.permission.{permission}")
adb("shell", "mkdir", "-p", "/sdcard/Music")
for f in [song, *songs]:
    adb("push", str(f), f"/sdcard/Music/{f.name}")
    if f is not song:
        f.unlink()
adb("logcat", "-c", check=False)
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
adb("forward", "tcp:5091", "tcp:5091")


def server_up():
    try:
        with urllib.request.urlopen("http://127.0.0.1:5091/manifest.webmanifest", timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


wait_for("the Python server inside the app did not start", 240, server_up)
print("server is up")
screenshot("started")


def find_song():
    nodes = ui_nodes()
    print("on screen:", sorted({n[0] for n in nodes})[:40])
    return next((n for n in nodes if n[0].strip() == SONG), None)


_, x, y = wait_for("the song is not listed in the library", 180, find_song)
screenshot("library")
adb("shell", "input", "tap", str(x), str(y))


def playing():
    dump = adb("shell", "dumpsys", "media_session", check=False)
    ours = [block for block in dump.split("\n\n") if PACKAGE in block]
    text = "\n".join(ours)
    return SONG in text and re.search(r"state=(PlaybackState \{state=)?(3|PLAYING)", text)


wait_for("Android's media session does not report the song as playing", 90, playing)


def console():
    return adb("logcat", "-d", "-s", "MFPConsole:I", check=False)


results = {}
for ext in FORMATS:
    title = f"Fmt {ext}"
    node = next((n for n in ui_nodes() if n[0].strip() == title), None)
    if not node:
        results[ext] = "not listed"
        continue
    adb("shell", "input", "tap", str(node[1]), str(node[2]))
    end = time.time() + 45
    results[ext] = "no sound after 45 s"
    while time.time() < end:
        lines = [l for l in console().splitlines() if title in l]
        if any("MFP_PLAYING" in l for l in lines):
            results[ext] = "ok"
            break
        errors = [l for l in lines if "MFP_ERROR" in l]
        if errors:
            results[ext] = "error: " + errors[-1].split("MFP_ERROR", 1)[1].strip()
            break
        time.sleep(2)
    print(f"{ext}: {results[ext]}")
screenshot("formats")
bad = {k: v for k, v in results.items() if v != "ok"}
if bad:
    finish(f"these formats do not play: {bad}")
finish()
