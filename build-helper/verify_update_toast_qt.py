"""End-to-end: fake build 104 on the packaged app and wait for update toast."""

import os
import sys
import time

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, ROOT)

from tools.clipflow_updater import start_app_updater


TOAST_SEEN = False


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

    def _show_update_available_toast(self):
        global TOAST_SEEN
        TOAST_SEEN = True
        print("update_toast_shown")
        QApplication.instance().quit()


def main():
    os.environ.setdefault("CLIPFLOW_BUILD_NUMBER", "104")
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    app = QApplication([])
    # Packaged builds set sys.frozen; mimic that so we exercise the real updater path.
    import tools.clipflow_updater as updater

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