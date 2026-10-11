import unittest

from tools import downloader_engine as engine


class OriginalAudioTests(unittest.TestCase):
    def test_original_audio_wins_over_larger_dub_and_combined_format(self):
        formats = [
            {"format_id": "137", "ext": "mp4", "height": 1080,
             "vcodec": "avc1", "acodec": "none"},
            {"format_id": "140-orig", "ext": "m4a", "vcodec": "none",
             "acodec": "aac", "language_preference": 10, "filesize": 100},
            {"format_id": "140-dub", "ext": "m4a", "vcodec": "none",
             "acodec": "aac", "language_preference": -1, "filesize": 1000},
            {"format_id": "96-dub", "ext": "mp4", "height": 1080,
             "vcodec": "avc1", "acodec": "aac", "language_preference": -1},
            {"format_id": "96-orig", "ext": "mp4", "height": 1080,
             "vcodec": "avc1", "acodec": "aac", "language_preference": 10},
        ]
        for fmt in formats:
            fmt["url"] = "https://media.test/" + fmt["format_id"]
        info = {"title": "Video", "webpage_url": "https://www.youtube.com/watch?v=test",
                "formats": formats}
        candidates = engine.candidates_from_info(info)
        self.assertEqual(engine.best_audio_format(formats)["format_id"], "140-orig")
        self.assertTrue(candidates)
        for candidate in candidates:
            self.assertNotIn("dub", candidate["format_selector"])
            self.assertTrue(candidate["original_audio"])
            self.assertFalse(engine.should_retry_progressive_mp4(
                candidate, RuntimeError("unable to download video data: HTTP Error 403")))
        video = next(c for c in candidates if c["format_id"] == "137")
        self.assertEqual(video["format_selector"], "137+140-orig[language_preference>=10]")
        audio = engine.candidates_from_info(info, output_ext="mp3")
        self.assertTrue(audio)
        self.assertTrue(all(c["format_id"] == "140-orig" for c in audio))


if __name__ == "__main__":
    unittest.main()
