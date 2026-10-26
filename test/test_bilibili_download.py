import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools import downloader_engine as engine


class Response(io.BytesIO):
    status = 206

    def __init__(self, data, start, end, total):
        super().__init__(data)
        self.headers = {"Content-Range": f"bytes {start}-{end}/{total}"}


class RangeServer:
    def __init__(self, data, fail_start=None, wrong_once=False):
        self.data = data
        self.calls = []
        self.fail_start = fail_start
        self.wrong_once = wrong_once

    def urlopen(self, request):
        start, end = map(int, request.headers["Range"].removeprefix("bytes=").split("-"))
        self.calls.append((start, end))
        if start == self.fail_start:
            raise OSError("interrupted")
        response = Response(self.data[start:end + 1], start, end, len(self.data))
        if self.wrong_once and end > start:
            self.wrong_once = False
            response.headers["Content-Range"] = f"bytes 1-{end + 1}/{len(self.data)}"
        return response


class BilibiliDownloadTests(unittest.TestCase):
    def test_host_matching(self):
        self.assertTrue(engine.is_bilibili_url("https://www.bilibili.com/video/a"))
        self.assertTrue(engine.is_bilibili_url("https://b23.tv/a"))
        self.assertFalse(engine.is_bilibili_url("https://bilibili.com.evil.test/a"))

    def test_wrong_range_retried_without_corrupting_output(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / "video.mp4"
            server = RangeServer(b"abcdefghijkl", wrong_once=True)
            info = {"url": "https://example.test/file", "format_id": "video"}
            with mock.patch("threading.Event.wait", return_value=False):
                engine.download_bilibili_stream(target, info, server, workers=1, chunk_size=4)
            self.assertEqual(target.read_bytes(), server.data)
            self.assertEqual(server.calls.count((0, 3)), 2)
            self.assertFalse(target.with_name(target.name + ".bili-parts").exists())

    def test_failure_preserves_only_completed_ranges_for_resume(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / "video.mp4"
            info = {"url": "https://example.test/file?token=old", "format_id": "video"}
            server = RangeServer(b"abcdefghijkl", fail_start=4)
            with mock.patch("threading.Event.wait", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "failed after retries"):
                    engine.download_bilibili_stream(target, info, server, workers=1, chunk_size=4)
            self.assertFalse(target.exists())
            self.assertEqual(server.calls.count((0, 3)), 1)
            self.assertEqual(server.calls.count((4, 7)), 4)
            server.fail_start = None
            server.calls.clear()
            info["url"] = "https://example.test/file?token=new"
            engine.download_bilibili_stream(target, info, server, workers=1, chunk_size=4)
            self.assertNotIn((0, 3), server.calls)
            self.assertEqual(target.read_bytes(), server.data)

    def test_changed_source_cannot_mix_with_saved_parts(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / "video.mp4"
            info = {"url": "https://example.test/file", "format_id": "video"}
            with mock.patch("threading.Event.wait", return_value=False):
                with self.assertRaises(RuntimeError):
                    engine.download_bilibili_stream(target, info, RangeServer(b"abcdefgh", fail_start=4), workers=1, chunk_size=4)
            with self.assertRaisesRegex(RuntimeError, "source changed"):
                engine.download_bilibili_stream(target, info, RangeServer(b"abcdefghijkl"), workers=1, chunk_size=4)
            self.assertFalse(target.exists())

    def test_factory_is_scoped_and_clip_download_uses_ytdlp(self):
        from yt_dlp import YoutubeDL
        from yt_dlp.extractor.bilibili import BiliBiliIE
        factory = engine.bilibili_download_factory(YoutubeDL)
        with factory({"quiet": True, "download_ranges": lambda *args: []}) as ydl:
            self.assertNotEqual(type(ydl.get_info_extractor("BiliBili")), BiliBiliIE)
            with mock.patch.object(YoutubeDL, "dl", return_value=(True, True)) as original:
                ydl.dl("test.mp4", {"_clipflow_bilibili_urls": ["https://example.test/file"]})
                original.assert_called_once()
        with YoutubeDL({"quiet": True}) as ydl:
            self.assertEqual(type(ydl.get_info_extractor("BiliBili")), BiliBiliIE)
