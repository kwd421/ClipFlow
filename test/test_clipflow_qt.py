import os
import subprocess
import sys
import unittest


class ClipFlowQtTests(unittest.TestCase):
    def test_clipflow_qt_smoke_launches_offscreen(self):
        env = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "CLIPFLOW_QT_SMOKE": "1"}
        result = subprocess.run(
            [sys.executable, "tools/clipflow_qt.py"],
            capture_output=True,
            text=True,
            env=env,
            timeout=20,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ClipFlow smoke launch OK", result.stdout)

    def test_clipflow_qt_fake_analysis_populates_grouped_candidates(self):
        env = {**os.environ, "QT_QPA_PLATFORM": "offscreen"}
        script = r'''
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication
from tools.clipflow_qt import ClipFlowWindow

def fake_analyze(url, cookie_source=None, proxy_url=None, output_ext=None, on_event=None):
    if on_event:
        on_event({"type": "status", "message": "fake analyzing"})
    return {
        "url": url,
        "title": "Fake",
        "candidates": [
            {"id": "720", "source": "s", "title": "Video", "display_title": "Video", "thumbnail": "", "ext": "mp4", "output_ext": "mp4", "resolution": "720p", "height": 720, "sort_bytes": 20},
            {"id": "1080", "source": "s", "title": "Video", "display_title": "Video", "thumbnail": "", "ext": "mp4", "output_ext": "mp4", "resolution": "1080p", "height": 1080, "sort_bytes": 30},
        ],
        "warnings": [],
    }

app = QApplication([])
window = ClipFlowWindow(analyze_func=fake_analyze)
window.url_input.setText("https://example.test/video")
window._handle_primary_action()

def check():
    if window.table.rowCount() == 1:
        print(window.table.rowCount())
        print(window.count_label.text())
        print(window.table.cellWidget(0, 3).count())
        print(window.selected_candidate_for_row(0)["id"])
        app.quit()

timer = QTimer()
timer.timeout.connect(check)
timer.start(20)
QTimer.singleShot(5000, app.quit)
app.exec()
'''
        result = subprocess.run(
            [sys.executable, "-c", script],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["1", "1개", "2", "1080"])

    def test_clipflow_qt_download_uses_selected_quality_candidate(self):
        env = {**os.environ, "QT_QPA_PLATFORM": "offscreen"}
        script = r'''
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication
from tools.clipflow_qt import ClipFlowWindow

downloaded = []

def fake_analyze(url, cookie_source=None, proxy_url=None, output_ext=None, on_event=None):
    return {
        "webpage_url": url,
        "url": url,
        "title": "Fake",
        "candidates": [
            {"id": "720", "source": "s", "title": "Video", "display_title": "Video", "thumbnail": "", "ext": "mp4", "output_ext": "mp4", "resolution": "720p", "height": 720, "sort_bytes": 20},
            {"id": "1080", "source": "s", "title": "Video", "display_title": "Video", "thumbnail": "", "ext": "mp4", "output_ext": "mp4", "resolution": "1080p", "height": 1080, "sort_bytes": 30},
        ],
        "warnings": [],
    }

def fake_download(page_url, candidate, output_dir, cookie_source=None, proxy_url=None, on_event=None):
    downloaded.append(candidate["id"])
    if on_event:
        on_event({"type": "progress", "percent": 42, "message": "fake downloading"})
        on_event({"type": "done", "path": output_dir})
    return {"ok": True, "output_dir": output_dir}

app = QApplication([])
window = ClipFlowWindow(analyze_func=fake_analyze, download_func=fake_download)
window.url_input.setText("https://example.test/video")
window._handle_primary_action()

def drive():
    if window.table.rowCount() == 1 and not downloaded:
        combo = window.table.cellWidget(0, 3)
        combo.setCurrentIndex(1)
        window.table.selectRow(0)
        window._handle_primary_action()
    if downloaded and window.table.item(0, 5).text() == "완료":
        print(downloaded[0])
        print(window.progress.value())
        print(window.table.item(0, 5).text())
        app.quit()

timer = QTimer()
timer.timeout.connect(drive)
timer.start(20)
QTimer.singleShot(5000, app.quit)
app.exec()
'''
        result = subprocess.run(
            [sys.executable, "-c", script],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["720", "100", "완료"])


if __name__ == "__main__":
    unittest.main()
