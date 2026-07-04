"""Gate release uploads: live appcast + older-build detection + toast callback path."""

import argparse
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPARKLE_VERSION = "{http://www.andymatuschak.org/xml-namespaces/sparkle}version"
PAGES_FEED = "https://kwd421.github.io/ClipFlow/appcast-windows.xml"
GITHUB_FEED = "https://raw.githubusercontent.com/kwd421/ClipFlow/main/docs/appcast-windows.xml"


def _latest_build(feed_url):
    request = urllib.request.Request(feed_url, headers={"User-Agent": "ClipFlow-Release-Verify"})
    with urllib.request.urlopen(request, timeout=20) as response:
        root = ET.fromstring(response.read())
    item = root.find("channel/item")
    if item is None:
        raise RuntimeError(f"no appcast item: {feed_url}")
    version = item.find(SPARKLE_VERSION)
    if version is None or not version.text:
        raise RuntimeError(f"no sparkle:version: {feed_url}")
    return int(version.text.strip())


def _run(script, *args):
    cmd = [sys.executable, str(ROOT / "build-helper" / script), *map(str, args)]
    return subprocess.run(cmd, cwd=ROOT, check=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", required=True, help="Current release build number, e.g. 105")
    args = parser.parse_args()
    current = int(args.build)
    previous = current - 1
    if previous < 1:
        raise SystemExit("build number must be > 1")

    errors = []

    for name, feed in (("pages", PAGES_FEED), ("github", GITHUB_FEED)):
        try:
            latest = _latest_build(feed)
        except Exception as exc:
            errors.append(f"{name}_appcast_error={exc}")
            continue
        print(f"{name}_appcast_latest={latest}")
        if latest != current:
            errors.append(f"{name}_appcast_expected_{current}_got_{latest}")

    older = _run("verify_update_check.py", previous)
    print(f"older_build_{previous}_sees_update exit={older.returncode}")
    if older.returncode != 0:
        errors.append(f"older_build_{previous}_must_see_update")

    same = _run("verify_update_check.py", current)
    print(f"current_build_{current}_sees_update exit={same.returncode}")
    if same.returncode == 0:
        errors.append(f"current_build_{current}_must_not_see_update")

    toast = _run("verify_update_toast_qt.py")
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