import gc
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlparse

from yt_dlp import YoutubeDL
from yt_dlp.downloader.dash import DashSegmentsFD
from yt_dlp.extractor.youtube import YoutubeIE
from yt_dlp.utils import DownloadError

from tools import downloader_engine as engine


class YoutubeLiveTests(unittest.TestCase):
    def test_live_candidates_append_without_exhausting_open_ended_generator(self):
        info = {
            "id": "live-test", "title": "Live",
            "webpage_url": "https://www.youtube.com/watch?v=live-test",
            "is_live": True, "duration": 100,
            "formats": [{"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "aac", "height": 360, "filesize": 1000,
                         "url": "https://example.com/video.mp4"}],
        }
        candidates = engine.candidates_from_info(info)
        self.assertTrue(candidates)
        with tempfile.TemporaryDirectory() as folder:
            for candidate in candidates:
                self.assertTrue(candidate["is_live"])
                self.assertEqual(candidate["duration"], 0)
                self.assertEqual(candidate["sort_bytes"], 0)
                options = engine.build_download_options(candidate, folder)
                self.assertTrue(options["live_from_start"])
                self.assertEqual(options["concurrent_fragment_downloads"], 1)
                self.assertFalse(options["skip_unavailable_fragments"])
                self.assertNotIn("download_ranges", options)
                self.assertNotIn("external_downloader", options)

    def test_other_sites_keep_existing_download_mode(self):
        with tempfile.TemporaryDirectory() as folder:
            options = engine.build_download_options(
                {"source": "https://example.com/video", "title": "Video"}, folder)
        self.assertNotIn("live_from_start", options)

    def test_live_progress_does_not_treat_current_edge_as_final_length(self):
        events = []
        engine.progress_hook(events.append)({
            "status": "downloading", "downloaded_bytes": 1024,
            "total_bytes_estimate": 1024, "speed": 512,
            "_speed_str": "512 B/s", "info_dict": {"is_live": True},
        })
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["total"], 0)
        self.assertEqual(events[0]["speed"], 512)
        self.assertEqual(events[0]["percent"], 0)

    def test_youtube_live_to_ended_transition_includes_last_sequence(self):
        with YoutubeDL({"quiet": True}) as ydl:
            extractor = YoutubeIE(ydl)
            feed = Mock(side_effect=[("https://example.com/media", True),
                                     ("https://example.com/media", False)])
            replies = [Mock(headers={"X-Head-Seqnum": str(i)}) for i in (1, 2)]
            with patch.object(extractor, "_request_webpage", side_effect=replies), \
                    patch("yt_dlp.extractor.youtube._video.time.sleep"):
                fragments = list(extractor._live_adaptive_fragments(
                    "live-test", "137", "web", None, feed, None, 5, {}, {}))
        self.assertEqual([parse_qs(urlparse(f["url"]).query)["sq"][0]
                          for f in fragments], ["0", "1", "2"])
        self.assertEqual(feed.call_count, 2)


class YoutubeLiveTransferTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.requests = []
        self.fail_at = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                index = int(self.path.strip("/"))
                owner.requests.append(index)
                if owner.fail_at == index:
                    self.send_error(503)
                    return
                content = bytes([index]) * 8192
                self.send_response(200)
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)
        self.output = Path(self.folder.name) / "live.mp4"
        self.candidate = {
            "title": "live", "source": "https://www.youtube.com/watch?v=live-test",
            "is_live": True, "format_selector": "137", "output_ext": "mp4",
        }

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def transfer(self):
        def fragments(ctx):
            for index in range(1, 4):
                # The previous fragment must be on disk before asking for the next.
                if index > 1:
                    self.assertGreaterEqual(
                        Path(str(self.output) + ".part").stat().st_size, (index - 1) * 8192)
                yield {"url": f"http://127.0.0.1:{self.server.server_port}/{index}"}

        options = engine.build_download_options(self.candidate, self.folder.name)
        options.update({"retries": 0, "fragment_retries": 0, "noprogress": True})
        with YoutubeDL(options) as ydl:
            downloader = DashSegmentsFD(ydl, options)
            return downloader.download(str(self.output), {
                "id": "live-test", "title": "live", "ext": "mp4",
                "protocol": "http_dash_segments_generator",
                "is_live": True, "is_from_start": True, "fragments": fragments,
            })

    def test_live_appends_during_capture_and_finalizes_last_fragment(self):
        self.transfer()
        self.assertEqual(self.output.read_bytes(), b"".join(bytes([i]) * 8192 for i in range(1, 4)))
        self.assertFalse(Path(str(self.output) + ".part").exists())
        self.assertFalse(Path(str(self.output) + ".ytdl").exists())

    def test_failed_fragment_is_not_skipped_and_resume_keeps_earlier_bytes(self):
        self.fail_at = 2
        with self.assertRaises(DownloadError):
            self.transfer()
        # Release yt-dlp's exception cycles before reopening the file on Windows.
        gc.collect()
        self.assertFalse(self.output.exists())
        self.assertEqual(Path(str(self.output) + ".part").stat().st_size, 8192)
        self.fail_at = None
        self.requests.clear()
        self.transfer()
        self.assertEqual(self.requests, [2, 3])
        self.assertEqual(self.output.read_bytes(), b"".join(bytes([i]) * 8192 for i in range(1, 4)))

    def test_engine_keeps_live_partial_files_on_restart(self):
        with patch.object(engine, "cleanup_partial_output_files") as cleanup, \
                patch.object(engine, "youtube_dl_factory") as factory:
            engine.download_candidate(self.candidate["source"], self.candidate, self.folder.name)
        cleanup.assert_not_called()
        factory.return_value.return_value.__enter__.return_value.download.assert_called_once()


if __name__ == "__main__":
    unittest.main()
