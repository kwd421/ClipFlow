"""End-to-end: fake older build and wait for update toast callback."""

import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, ROOT)

from tools.clipflow_updater import ensure_main_thread_dispatcher, start_app_updater


TOAST_SEEN = False
SPARKLE_VERSION = "{http://www.andymatuschak.org/xml-namespaces/sparkle}version"
SPARKLE_SHORT = "{http://www.andymatuschak.org/xml-namespaces/sparkle}shortVersionString"
SPARKLE_NOTES = "{http://www.andymatuschak.org/xml-namespaces/sparkle}releaseNotesLink"


class _Window:
    def __init__(self):
        self.update_toast = None

    def schedule_startup_update_check(self):
        updater = getattr(QApplication.instance(), "_clipflow_updater", None)
        if updater is None:
            print("updater_missing")
            QApplication.instance().quit()
            return
        updater.schedule_startup_check(self._show_update_available_toast)

    def _show_update_available_toast(self, info=None):
        global TOAST_SEEN
        TOAST_SEEN = True
        print("update_toast_shown")
        if isinstance(info, dict) and info.get("version"):
            print(f"update_version={info.get('version')}")
        QApplication.instance().quit()


def _local_fetch_startup_update_info():
    appcast_path = os.environ.get("CLIPFLOW_VERIFY_APPCAST_PATH", "").strip()
    current_text = os.environ.get("CLIPFLOW_BUILD_NUMBER", "").strip()
    current = int(current_text) if current_text.isdigit() else None
    if not appcast_path or current is None:
        from tools.clipflow_updater import fetch_startup_update_info

        return fetch_startup_update_info()
    root = ET.fromstring(Path(appcast_path).read_bytes())
    item = root.find("channel/item")
    if item is None:
        return None
    version_el = item.find(SPARKLE_VERSION)
    latest = int(version_el.text.strip()) if version_el is not None and version_el.text else None
    if latest is None or latest <= current:
        return None
    short_el = item.find(SPARKLE_SHORT)
    short_version = (short_el.text or "").strip() if short_el is not None else ""
    if not short_version:
        title_el = item.find("title")
        short_version = (title_el.text or "").strip() if title_el is not None else str(latest)
    notes_el = item.find(SPARKLE_NOTES)
    if notes_el is None:
        notes_el = item.find("link")
    notes_url = (notes_el.text or "").strip() if notes_el is not None else ""
    return {
        "build": latest,
        "version": short_version,
        "release_notes_url": notes_url,
    }


def main():
    os.environ.setdefault("CLIPFLOW_BUILD_NUMBER", "104")
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    app = QApplication([])
    ensure_main_thread_dispatcher()
    # Packaged builds set sys.frozen; mimic that so we exercise the real updater path.
    import tools.clipflow_updater as updater

    if os.environ.get("CLIPFLOW_VERIFY_APPCAST_PATH"):
        updater.fetch_startup_update_info = _local_fetch_startup_update_info
        updater.startup_update_is_available = lambda: _local_fetch_startup_update_info() is not None

    if not getattr(sys, "frozen", False):
        sys.frozen = True  # type: ignore[attr-defined]
        updater.sys.frozen = True  # type: ignore[attr-defined]
    app._clipflow_updater = start_app_updater()
    if app._clipflow_updater is None:
        print("updater_missing")
        return 1
    window = _Window()
    QTimer.singleShot(1500, window.schedule_startup_update_check)
    QTimer.singleShot(8000, app.quit)
    app.exec()
    if not TOAST_SEEN:
        print("update_toast_missing")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
