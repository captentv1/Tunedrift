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
