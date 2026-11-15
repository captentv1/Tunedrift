"""Makes the Download tab's module (MusicFlow's server and page, and its Python packages) for the release:
  make_module.py android|windows <version> <out.zip>
The app downloads it from the release when the person installs the Download tab."""
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MF = ROOT / "musicflow"
platform, version, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
build = ROOT / "build-module" / platform
shutil.rmtree(build, ignore_errors=True)
build.mkdir(parents=True)

code = MF / ("android/app/src/main/python" if platform == "android" else "desktop")
for d in (code, MF / "core"):
    for f in d.glob("*.py"):
        shutil.copy2(f, build / f.name)
shutil.copy2(MF / "web" / "index.html", build / "index.html")

# Packages: pure Python on Android (yt-dlp); on Windows also ffmpeg, selenium and the yt-dlp helpers.
# Packages the player already has (Flask, mutagen…) are left out: the player's copies are used.
packages = ["yt-dlp"] if platform == "android" else ["yt-dlp", "yt-dlp-ejs", "imageio-ffmpeg", "selenium"]
cmd = [sys.executable, "-m", "pip", "install", "-q", "--target", str(build)] + packages
if platform == "android":
    cmd.insert(5, "--no-deps")
subprocess.check_call(cmd)
for name in ("flask", "werkzeug", "jinja2", "markupsafe", "click", "itsdangerous", "blinker", "mutagen", "bin"):
    shutil.rmtree(build / name, ignore_errors=True)
for p in list(build.rglob("__pycache__")):
    shutil.rmtree(p, ignore_errors=True)
(build / "module.json").write_text(json.dumps({"version": version, "platform": platform}), encoding="utf-8")

out.unlink(missing_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(build.rglob("*")):
        if f.is_file():
            z.write(f, f.relative_to(build).as_posix())
print(f"{out}: {out.stat().st_size // 1024} KB")
