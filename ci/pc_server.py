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
from werkzeug.serving import make_server  # noqa: E402
make_server("0.0.0.0", s.PORT, s.app, threaded=True).serve_forever()
