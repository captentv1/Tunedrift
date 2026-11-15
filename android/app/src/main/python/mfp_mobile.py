import os


def start(data_dir: str, token_value: str, default_folders: str, browse_roots: str):
    os.environ["MFP_DATA_DIR"] = data_dir
    os.environ["MFP_DEFAULT_FOLDERS"] = default_folders
    os.environ["MFP_BROWSE_ROOTS"] = browse_roots
    import mfp_server as app
    # Security: the page is loaded by the app itself with the token; the server never writes it into a page
    # (another app on the phone could otherwise read it from http://127.0.0.1:5091/).
    app.CONFIG["token"] = token_value
    app.CONFIG["token_in_page"] = False
    app._scan_async()
    app.app.run(host="127.0.0.1", port=app.PORT, debug=False, threaded=True, use_reloader=False)


def start_musicflow(dest: str, config_path: str, token_value: str) -> bool:
    """Starts MusicFlow (search and download, its own Android server) for the Download tab."""
    import threading
    import mfp_server
    import mobile_app as mf  # MusicFlow's Android server (submodule)
    import musicflow_embed
    # Security: like the player, MusicFlow never writes its token into a page; the app gives it to the frame.
    mf.configure(dest, config_path, token_value, False)
    musicflow_embed.for_player(mf.app)
    threading.Thread(target=mf.start_server, daemon=True).start()
    mfp_server.use_musicflow(dest, musicflow_embed.URL)
    return True
