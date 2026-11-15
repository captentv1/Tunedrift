"""Prints PyInstaller options that put Python's whole standard library in the exe: the Download tab's
module (MusicFlow, yt-dlp, selenium) is installed later and may import any part of it."""
import importlib.util
import sys

SKIP = {"test", "idlelib", "tkinter", "turtle", "turtledemo", "lib2to3", "ensurepip", "venv", "pydoc_data",
        "distutils", "antigravity", "this"}
for name in sorted(sys.stdlib_module_names):
    if name in SKIP or name.startswith(("_test", "__")):
        continue
    try:
        spec = importlib.util.find_spec(name)
    except Exception:
        continue
    if spec:
        print(f"--collect-submodules={name}" if spec.submodule_search_locations else f"--hidden-import={name}")
