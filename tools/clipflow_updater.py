"""Sparkle updater bridge for packaged macOS builds."""

import plistlib
import sys
from pathlib import Path


class SparkleUpdater:
    def __init__(self, controller):
        self.controller = controller

    def check_for_updates(self):
        self.controller.checkForUpdates_(None)


def _running_app_bundle():
    if sys.platform != "darwin" or not getattr(sys, "frozen", False):
        return None
    executable = Path(sys.executable).resolve()
    contents_dir = executable.parent.parent
    if contents_dir.name != "Contents":
        return None
    app_bundle = contents_dir.parent
    if app_bundle.suffix != ".app":
        return None
    return app_bundle


def _sparkle_configured(app_bundle):
    plist_path = app_bundle / "Contents" / "Info.plist"
    try:
        with plist_path.open("rb") as handle:
            info = plistlib.load(handle)
    except (OSError, plistlib.InvalidFileException):
        return False
    return bool(info.get("SUFeedURL") and info.get("SUPublicEDKey"))


def start_sparkle_updater():
    app_bundle = _running_app_bundle()
    if not app_bundle or not _sparkle_configured(app_bundle):
        return None

    framework_path = app_bundle / "Contents" / "Frameworks" / "Sparkle.framework"
    if not framework_path.exists():
        return None

    try:
        import objc
        from Foundation import NSBundle
    except ImportError:
        return None

    bundle = NSBundle.bundleWithPath_(str(framework_path))
    if not bundle or not bundle.load():
        return None

    try:
        updater_class = objc.lookUpClass("SPUStandardUpdaterController")
        controller = updater_class.alloc().initWithStartingUpdater_updaterDelegate_userDriverDelegate_(True, None, None)
    except (objc.error, AttributeError):
        return None

    return SparkleUpdater(controller)
