import os
import sys
import unittest
from unittest import mock

from tools import clipflow_updater as updater


class ClipFlowUpdaterTests(unittest.TestCase):
    def test_updater_feed_url_prefers_platform_specific_env_on_windows(self):
        with mock.patch.object(updater.sys, "platform", "win32"):
            with mock.patch.dict(
                os.environ,
                {
                    "CLIPFLOW_WINSPARKLE_FEED_URL": "https://example.test/windows.xml",
                    "CLIPFLOW_SPARKLE_FEED_URL": "https://example.test/macos.xml",
                },
                clear=False,
            ):
                self.assertEqual(updater.updater_feed_url(), "https://example.test/windows.xml")

    def test_updater_feed_url_uses_sparkle_env_on_macos(self):
        with mock.patch.object(updater.sys, "platform", "darwin"):
            with mock.patch.dict(
                os.environ,
                {
                    "CLIPFLOW_WINSPARKLE_FEED_URL": "https://example.test/windows.xml",
                    "CLIPFLOW_SPARKLE_FEED_URL": "https://example.test/macos.xml",
                },
                clear=False,
            ):
                self.assertEqual(updater.updater_feed_url(), "https://example.test/macos.xml")

    def test_updater_configured_reads_baked_values_in_frozen_build(self):
        baked = mock.Mock(
            FEED_URL="https://example.test/windows.xml",
            PUBLIC_ED_KEY="abc123",
            VERSION="1.2.3",
            BUILD_NUMBER="123",
        )
        with mock.patch.object(updater.sys, "frozen", True, create=True):
            with mock.patch.dict(os.environ, {}, clear=True):
                with mock.patch.object(updater, "_frozen_build_config", return_value=baked):
                    self.assertTrue(updater.updater_configured())
                    self.assertEqual(updater.updater_feed_url(), "https://example.test/windows.xml")
                    self.assertEqual(updater.updater_public_ed_key(), "abc123")
                    self.assertEqual(updater.updater_app_version(), "1.2.3")
                    self.assertEqual(updater.updater_build_number(), "123")

    def test_updater_configured_requires_feed_and_public_key(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertFalse(updater.updater_configured())
        with mock.patch.dict(
            os.environ,
            {
                "CLIPFLOW_WINSPARKLE_FEED_URL": "https://example.test/windows.xml",
                "CLIPFLOW_SPARKLE_PUBLIC_ED_KEY": "abc123",
            },
            clear=False,
        ):
            self.assertTrue(updater.updater_configured())

    def test_start_app_updater_returns_none_in_dev_mode(self):
        with mock.patch.object(updater.sys, "frozen", False, create=True):
            self.assertIsNone(updater.start_app_updater())

    def test_start_winsparkle_updater_requires_frozen_build(self):
        with mock.patch.object(updater.sys, "frozen", False, create=True):
            self.assertIsNone(updater.start_winsparkle_updater())

    def test_winsparkle_updater_schedule_startup_check_triggers_background_check(self):
        library = mock.Mock()
        instance = updater.WinSparkleUpdater(library)
        seen = []

        def on_found():
            seen.append(True)

        instance.schedule_startup_check(on_found)
        library.win_sparkle_check_update_without_ui.assert_called_once_with()
        self.assertIs(instance._on_found, on_found)


if __name__ == "__main__":
    unittest.main()