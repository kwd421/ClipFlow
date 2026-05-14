import tempfile
import unittest
from pathlib import Path

from tools import headless_verification


class HeadlessVerificationTests(unittest.TestCase):
    def test_writes_analysis_without_download(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            out_path = Path(temp_dir) / "verify.json"

            def fake_analyze(url, cookie_source=None, proxy_url=None, output_ext=None, on_event=None):
                self.assertEqual(cookie_source, "없음")
                self.assertIsNone(proxy_url)
                self.assertIsNone(output_ext)
                self.assertTrue(callable(on_event))
                return {
                    "url": url,
                    "title": "Fake",
                    "candidates": [
                        {
                            "id": "1",
                            "format_id": "18",
                            "format_selector": "18",
                            "url": url,
                            "title": "Fake",
                            "sort_bytes": 123,
                        }
                    ],
                    "warnings": [],
                }

            result = headless_verification.run_headless_verification(
                url="https://example.test/video",
                output_json=str(out_path),
                output_dir=temp_dir,
                cookie_source="없음",
                should_download=False,
                analyze_func=fake_analyze,
            )

            self.assertTrue(result["ok"])
            self.assertEqual(result["candidate_count"], 1)
            self.assertTrue(out_path.exists())

    def test_passes_proxy_without_ui(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            out_path = Path(temp_dir) / "verify.json"

            def fake_analyze(url, cookie_source=None, proxy_url=None, output_ext=None, on_event=None):
                self.assertEqual(proxy_url, "http://127.0.0.1:8080")
                return {
                    "url": url,
                    "title": "Fake",
                    "candidates": [
                        {
                            "id": "1",
                            "format_id": "18",
                            "format_selector": "18",
                            "url": url,
                            "title": "Fake",
                            "sort_bytes": 123,
                        }
                    ],
                    "warnings": [],
                }

            result = headless_verification.run_headless_verification(
                url="https://example.test/video",
                output_json=str(out_path),
                output_dir=temp_dir,
                cookie_source="없음",
                proxy_url="http://127.0.0.1:8080",
                should_download=False,
                analyze_func=fake_analyze,
            )

            self.assertTrue(result["ok"])

    def test_can_download_selected_candidate_index(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            out_path = Path(temp_dir) / "verify.json"
            downloaded = []

            def fake_analyze(url, cookie_source=None, proxy_url=None, output_ext=None, on_event=None):
                return {
                    "url": url,
                    "title": "Fake",
                    "candidates": [
                        {"id": "high", "format_selector": "high", "output_ext": "mp4", "ext": "mp4"},
                        {"id": "low", "format_selector": "low", "output_ext": "mp4", "ext": "mp4"},
                    ],
                    "warnings": [],
                }

            def fake_download(page_url, candidate, output_dir, cookie_source=None, proxy_url=None, on_event=None):
                downloaded.append(candidate["id"])
                path = Path(output_dir) / "fake.mp4"
                path.write_bytes(b"mp4")
                return {"ok": True, "output_dir": output_dir}

            result = headless_verification.run_headless_verification(
                url="https://example.test/video",
                output_json=str(out_path),
                output_dir=temp_dir,
                should_download=True,
                download_candidate_index=1,
                analyze_func=fake_analyze,
                download_func=fake_download,
            )

            self.assertTrue(result["ok"])
            self.assertEqual(downloaded, ["low"])
            self.assertEqual(result["download"]["selected_candidate"]["id"], "low")


if __name__ == "__main__":
    unittest.main()
