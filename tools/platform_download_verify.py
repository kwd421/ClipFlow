#!/usr/bin/env python3
import json
import os
import sys
import time
from pathlib import Path

from tools import downloader_engine as engine


def verify_cookie_source():
    return os.environ.get("CLIPFLOW_VERIFY_COOKIE_SOURCE") or os.environ.get("UMP4_VERIFY_COOKIE_SOURCE") or "없음"

PLATFORMS = [
    ("youtube", "https://www.youtube.com/watch?v=jNQXAC9IVRw", "yt-dlp"),
    ("chzzk", "https://chzzk.naver.com/clips/z0DUTFaKDZ", "yt-dlp"),
    ("instagram", "https://www.instagram.com/reel/DUl354timp1/", "yt-dlp"),
    ("vimeo", "https://vimeo.com/76979871", "yt-dlp"),
    ("tiktok", "https://vm.tiktok.com/ZMh8xPqKb/", "yt-dlp"),
    ("pornhub", "https://www.pornhub.com/view_video.php?viewkey=6861453eadd37", "dom"),
    ("xvideos", "https://www.xvideos.com/video.klvdubb4d47/49079845/0/full_version_https_bit.ly_36cjbb4", "dom"),
    ("redtube", "https://www.redtube.com/198689351", "dom"),
    ("xhamster", "https://xhamster.desi/videos/femaleagent-shy-beauty-takes-the-bait-1509445", "dom"),
]


def pick_verify_candidate(candidates, expected_path="yt-dlp"):
    candidates = list(candidates or [])
    if not candidates:
        return None
    if expected_path != "dom":
        for candidate in candidates:
            if safe_int(candidate.get("height")) > 0 and str(candidate.get("ext") or "mp4").lower() == "mp4":
                return candidate
        return candidates[0]
    direct_mp4 = [
        candidate
        for candidate in candidates
        if not candidate.get("is_manifest")
        and not engine.is_browser_remote_media_api_url(candidate.get("url"))
        and str(candidate.get("url") or "").lower().startswith(("http://", "https://"))
    ]
    remote_mp4_api = [
        candidate
        for candidate in candidates
        if not candidate.get("is_manifest")
        and engine.is_browser_remote_media_api_url(candidate.get("url"))
        and "/media/mp4" in str(candidate.get("url") or "").lower()
    ]
    if remote_mp4_api:
        return min(
            remote_mp4_api,
            key=lambda candidate: (
                safe_int(candidate.get("sort_bytes")) or 10**18,
                safe_int(candidate.get("height")) or 9999,
            ),
        )
    if direct_mp4:
        return min(
            direct_mp4,
            key=lambda candidate: (
                safe_int(candidate.get("sort_bytes")) or 10**18,
                safe_int(candidate.get("height")) or 9999,
            ),
        )
    manifest = [candidate for candidate in candidates if candidate.get("is_manifest")]
    if manifest:
        return max(manifest, key=lambda candidate: safe_int(candidate.get("height")) or 0)
    return candidates[-1]


def safe_int(value):
    return engine.safe_int(value)


def verify_platform(name, url, expected_path, out_root):
    started = time.time()
    row = {
        "platform": name,
        "mode": "live",
        "url": url,
        "expected_path": expected_path,
        "ok": False,
        "analyze_ok": False,
        "download_ok": False,
        "source": None,
        "title": None,
        "candidate_count": 0,
        "file_path": "",
        "file_bytes": 0,
        "error": "",
        "elapsed_seconds": 0,
    }
    try:
        analysis = engine.analyze_url(url, cookie_source=verify_cookie_source())
        row["analyze_ok"] = True
        row["source"] = analysis.get("source")
        row["title"] = analysis.get("title")
        candidates = analysis.get("candidates") or []
        row["candidate_count"] = len(candidates)
        if not candidates:
            raise RuntimeError("No candidates returned from analysis.")
        candidate = pick_verify_candidate(candidates, expected_path)
        if not candidate:
            raise RuntimeError("No downloadable candidate could be selected.")
        out_dir = out_root / name / str(int(time.time() * 1000))
        out_dir.mkdir(parents=True, exist_ok=True)
        before = time.time()
        engine.download_candidate(url, candidate, out_dir, cookie_source=verify_cookie_source())
        newest = engine.newest_file(out_dir, candidate.get("output_ext") or "mp4", since=before - 2)
        if not newest or not newest.exists():
            raise RuntimeError("Download finished but no output file was found.")
        row["download_ok"] = True
        row["file_path"] = str(newest)
        row["file_bytes"] = newest.stat().st_size
        row["ok"] = row["file_bytes"] > 50_000
        if not row["ok"]:
            row["error"] = f"Output file too small: {row['file_bytes']} bytes"
    except Exception as exc:
        row["error"] = str(exc)
    row["elapsed_seconds"] = round(time.time() - started, 2)
    return row


def main():
    out_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("downloads-verify")
    out_root.mkdir(parents=True, exist_ok=True)
    results = [verify_platform(name, url, path, out_root) for name, url, path in PLATFORMS]
    summary_path = out_root / "platform-download-verify.json"
    summary_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    passed = sum(1 for row in results if row["ok"])
    print(json.dumps({"passed": passed, "total": len(results), "summary": str(summary_path)}, ensure_ascii=False))
    for row in results:
        status = "OK" if row["ok"] else "FAIL"
        print(f"{status} {row['platform']}: {row.get('file_bytes', 0)} bytes source={row.get('source')} err={row.get('error','')[:120]}")


if __name__ == "__main__":
    main()