import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ReleaseConfigTests(unittest.TestCase):
    def test_windows_release_workflow_uses_winsparkle_public_key_secret(self):
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")

        self.assertIn(
            "CLIPFLOW_WINSPARKLE_PUBLIC_ED_KEY: ${{ secrets.CLIPFLOW_WINSPARKLE_PUBLIC_ED_KEY }}",
            workflow,
        )
        self.assertNotIn(
            "CLIPFLOW_SPARKLE_PUBLIC_ED_KEY: ${{ secrets.CLIPFLOW_SPARKLE_PUBLIC_ED_KEY }}",
            workflow,
        )

    def test_windows_release_script_does_not_read_macos_sparkle_public_key(self):
        script = (ROOT / "build-helper" / "release_windows.ps1").read_text(encoding="utf-8")

        self.assertIn("CLIPFLOW_WINSPARKLE_PUBLIC_ED_KEY", script)
        self.assertNotIn("CLIPFLOW_SPARKLE_PUBLIC_ED_KEY", script)

    def test_windows_pyinstaller_config_reads_winsparkle_public_key(self):
        spec = (ROOT / "build-helper" / "ClipFlow.spec").read_text(encoding="utf-8")

        self.assertIn('WIN_PUBLIC_ED_KEY = os.environ.get("CLIPFLOW_WINSPARKLE_PUBLIC_ED_KEY") or ""', spec)
        self.assertNotIn('WIN_PUBLIC_ED_KEY = os.environ.get("CLIPFLOW_SPARKLE_PUBLIC_ED_KEY") or ""', spec)

    def test_release_notes_exist_for_next_windows_release_version(self):
        notes = ROOT / "docs" / "ClipFlow-1.1.0.md"

        self.assertTrue(notes.exists())
        self.assertIn("# ClipFlow 1.1.0", notes.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
