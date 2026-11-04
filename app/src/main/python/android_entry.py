"""Called from Kotlin (ServerService). Starts the unchanged Cloud Vault server inside the app process."""
import os, threading, traceback

_state = {"started": False, "error": ""}


def start(port, data_dir, cache_dir):
    if _state["started"]:
        return True
    os.environ["VAULT_ANDROID"] = "1"
    os.environ["VAULT_DATA_DIR"] = data_dir
    os.environ["VAULT_HOST"] = "127.0.0.1"          # never listen on the network
    os.environ["VAULT_NO_CRYPTG"] = "1"
    os.environ["TMPDIR"] = cache_dir
    os.environ.setdefault("HOME", data_dir)
    os.makedirs(data_dir, exist_ok=True)

    def _run():
        try:
            import server
            server.run_main("127.0.0.1", int(port))
        except BaseException:
            _state["error"] = traceback.format_exc()
            try:
                with open(os.path.join(data_dir, "android_crash.log"), "w") as f:
                    f.write(_state["error"])
            except Exception:
                pass

    threading.Thread(target=_run, daemon=True, name="vault-main").start()
    _state["started"] = True
    return True


def last_error():
    return _state["error"]
