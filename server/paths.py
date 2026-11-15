from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CORE_DIR = ROOT / "musicflow" / "core"
WEB_DIR = next((d for d in (ROOT / "web", HERE / "web") if (d / "index.html").is_file()), HERE)  # source, exe, Android


def data_dir() -> Path:
    custom = os.environ.get("MFP_DATA_DIR")
    if custom:
        d = Path(custom)
    elif os.name == "nt":
        d = Path(os.environ.get("APPDATA", Path.home())) / "MusicFlowPlay"
    else:
        d = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "musicflow-play"
    d.mkdir(parents=True, exist_ok=True)
    return d


def use_core() -> None:
    if (CORE_DIR / "library.py").is_file():
        if str(CORE_DIR) not in sys.path:
            sys.path.insert(0, str(CORE_DIR))
        return
    try:
        import library  # noqa: F401  (Android: core/ is packed next to the server modules)
    except ImportError:
        sys.exit("musicflow/core is missing: run  git submodule update --init")
