"""Gate release uploads: local appcast + older-build detection + toast callback path."""

import argparse
import os
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPARKLE_VERSION = "{http://www.andymatuschak.org/xml-namespaces/sparkle}version"
PAGES_FEED = "https://kwd421.github.io/ClipFlow/appcast-windows.xml"
GITHUB_FEED = "https://raw.githubusercontent.com/kwd421/ClipFlow/main/docs/appcast-windows.xml"


def _latest_build_from_xml(root):
    item = root.find("channel/item")
    if item is None:
        raise RuntimeError("no appcast item")
    version = item.find(SPARKLE_VERSION)
    if version is None or not version.text:
        raise RuntimeError("no sparkle:version")
    return int(version.text.strip())


def _latest_build(feed_or_path):
    if feed_or_path.startswith("http://") or feed_or_path.startswith("https://"):
        request = urllib.request.Request(feed_or_path, headers={"User-Agent": "ClipFlow-Release-Verify"})
        with urllib.request.urlopen(request, timeout=20) as response:
            root = ET.fromstring(response.read())
    else:
        root = ET.fromstring(Path(feed_or_path).read_bytes())
    return _latest_build_from_xml(root)


def _run(script, *args, env=None):
    cmd = [sys.executable, str(ROOT / "build-helper" / script), *map(str, args)]
    merged = {**os.environ, **(env or {})}
    return subprocess.run(cmd, cwd=ROOT, check=False, env=merged)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", required=True, help="Current release build number, e.g. 106")
    parser.add_argument(
        "--appcast-path",
        default=str(ROOT / "docs" / "appcast-windows.xml"),
        help="Local appcast file that will be published with this release",
    )
    args = parser.parse_args()
    current = int(args.build)
    previous = current - 1
    if previous < 1:
        raise SystemExit("build number must be > 1")

    errors = []
    local_latest = _latest_build(args.appcast_path)
    print(f"local_appcast_latest={local_latest}")
    if local_latest != current:
        errors.append(f"local_appcast_expected_{current}_got_{local_latest}")

    if local_latest <= previous:
        errors.append(f"local_appcast_{local_latest}_must_be_newer_than_{previous}")
    else:
        print(f"older_build_{previous}_sees_update_from_local=True")

    if local_latest == current:
        print(f"current_build_{current}_sees_update_from_local=False")

    for name, feed in (("pages", PAGES_FEED), ("github", GITHUB_FEED)):
        try:
            latest = _latest_build(feed)
        except Exception as exc:
            print(f"{name}_appcast_latest=error ({exc})")
            continue
        print(f"{name}_appcast_latest={latest}")
        if latest > current:
            errors.append(f"{name}_appcast_ahead_of_release_{latest}")

    toast = _run(
        "verify_update_toast_qt.py",
        env={
            "CLIPFLOW_BUILD_NUMBER": str(previous),
            "CLIPFLOW_VERIFY_APPCAST_PATH": str(Path(args.appcast_path).resolve()),
        },
    )
    print(f"toast_path exit={toast.returncode}")
    if toast.returncode != 0:
        errors.append("toast_callback_path_failed")

    if errors:
        for item in errors:
            print(f"FAIL {item}", file=sys.stderr)
        return 1

    print("release_update_verify_ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())