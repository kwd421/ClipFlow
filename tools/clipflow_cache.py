"""Local app cache helpers for ClipFlow.

Thumbnail images are stored under ``%LOCALAPPDATA%/ClipFlow/cache/thumbnails``
keyed by URL hash. Call ``prune_thumbnail_cache`` whenever the download list
changes so files for removed cards are not left behind.
"""

from __future__ import annotations

import hashlib
import os
import tempfile
from pathlib import Path


def clipflow_data_root():
    base = os.environ.get("LOCALAPPDATA") or tempfile.gettempdir()
    return Path(base) / "ClipFlow"


def thumbnail_cache_dir():
    path = clipflow_data_root() / "cache" / "thumbnails"
    path.mkdir(parents=True, exist_ok=True)
    return path


def thumbnail_cache_key(url):
    text = str(url or "").strip()
    if not text:
        return ""
    return hashlib.sha1(text.encode("utf-8", errors="replace")).hexdigest()


def thumbnail_cache_path(url):
    key = thumbnail_cache_key(url)
    if not key:
        return None
    return thumbnail_cache_dir() / key


def load_cached_thumbnail_bytes(url):
    path = thumbnail_cache_path(url)
    if path is None or not path.is_file():
        return b""
    try:
        data = path.read_bytes()
    except OSError:
        return b""
    return data if data else b""


def store_cached_thumbnail_bytes(url, payload):
    path = thumbnail_cache_path(url)
    data = bytes(payload or b"")
    if path is None or not data:
        return None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    except OSError:
        return None
    return path


def collect_thumbnail_urls_from_rows(rows):
    """All remote thumbnail URLs still referenced by list cards."""
    urls = set()

    def add(value):
        text = str(value or "").strip()
        if text.lower().startswith(("http://", "https://")):
            urls.add(text)

    def add_candidate(candidate):
        if not isinstance(candidate, dict):
            return
        add(candidate.get("thumbnail"))
        add(candidate.get("thumbnail_url"))

    for row in rows or []:
        if not isinstance(row, dict):
            continue
        add_candidate(row.get("candidate") or {})
        for quality in row.get("qualities") or []:
            if isinstance(quality, dict):
                add_candidate(quality.get("candidate") or quality)
                for fmt in quality.get("formats") or []:
                    if isinstance(fmt, dict):
                        add_candidate(fmt.get("candidate") or fmt)
        for entry in row.get("playlist_entries") or []:
            if isinstance(entry, dict):
                add_candidate(entry.get("candidate") or {})
    return urls


def prune_thumbnail_cache(keep_urls):
    """Delete disk thumbnails whose URL is not in keep_urls.

    Returns the number of files removed.
    """
    keep_keys = {thumbnail_cache_key(url) for url in (keep_urls or set()) if thumbnail_cache_key(url)}
    root = thumbnail_cache_dir()
    removed = 0
    try:
        entries = list(root.iterdir())
    except OSError:
        return 0
    for path in entries:
        if not path.is_file():
            continue
        # Only manage our hash-named cache files (40-hex sha1).
        name = path.name
        if len(name) != 40 or any(ch not in "0123456789abcdef" for ch in name.lower()):
            continue
        if name.lower() in keep_keys:
            continue
        try:
            path.unlink()
            removed += 1
        except OSError:
            pass
    return removed


def prune_thumbnail_cache_for_rows(rows):
    return prune_thumbnail_cache(collect_thumbnail_urls_from_rows(rows))


def cleanup_stale_temp_artifacts(max_age_hours=1):
    """Remove leftover ClipFlow temp dirs/files under the system temp folder.

    Crash / forced-kill can leave TemporaryDirectory contents behind
    (``clipflow-hls-*``, ``clipflow-direct-*``, browser profiles, etc.).
    Only ages past ``max_age_hours`` are touched so live downloads are safe.
    """
    import shutil
    import time

    root = Path(tempfile.gettempdir())
    cutoff = time.time() - max(1, int(max_age_hours or 1)) * 3600
    removed = 0
    try:
        entries = list(root.iterdir())
    except OSError:
        return 0
    for path in entries:
        name = path.name
        lower = name.lower()
        if not (lower.startswith("clipflow-") or lower.startswith("clipflow_")):
            continue
        # Never delete the managed app cache root if someone pointed TEMP oddly.
        if lower in {"clipflow", "clipflow-cache"}:
            continue
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime > cutoff:
            continue
        try:
            if path.is_dir():
                shutil.rmtree(path, ignore_errors=False)
            else:
                path.unlink()
            removed += 1
        except OSError:
            pass
    return removed


def is_partial_artifact_name(name):
    """True for download resume/temp names, never complete media alone."""
    lower = str(name or "").lower()
    if not lower:
        return False
    if lower.endswith(".part") or lower.endswith(".part.media"):
        return True
    if ".trim.part." in lower:
        return True
    # yt-dlp fragment siblings: video.mp4.part-Frag1 / video.mp4.part.ytdl
    if ".part." in lower or ".part-" in lower:
        return True
    return False


def _resolve_path_key(path):
    try:
        return str(Path(path).expanduser().resolve())
    except OSError:
        return str(Path(path).expanduser())


def _iter_partial_scan_dirs(folder):
    """Save folder + one level of subdirs (playlist output folders)."""
    root = Path(folder).expanduser()
    if not root.is_dir():
        return
    yield root
    try:
        children = list(root.iterdir())
    except OSError:
        return
    for child in children:
        if child.is_dir():
            yield child


def cleanup_orphan_partial_outputs(folders, protected_paths=None, max_age_hours=1):
    """Delete stale ``.part*`` files in save folders that no active row owns.

    - Only names that look like download partials (never plain ``.mp4``).
    - Skip paths in ``protected_paths`` (current list / active downloads).
    - Skip files newer than ``max_age_hours`` so a live receive is safe.
    """
    import time

    protected = {_resolve_path_key(p) for p in (protected_paths or set()) if p}
    cutoff = time.time() - max(1, int(max_age_hours or 1)) * 3600
    removed = 0
    seen_dirs = set()
    for folder in folders or []:
        text = str(folder or "").strip()
        if not text:
            continue
        for directory in _iter_partial_scan_dirs(text):
            dir_key = _resolve_path_key(directory)
            if dir_key in seen_dirs:
                continue
            seen_dirs.add(dir_key)
            try:
                entries = list(directory.iterdir())
            except OSError:
                continue
            for path in entries:
                if not path.is_file() or not is_partial_artifact_name(path.name):
                    continue
                key = _resolve_path_key(path)
                if key in protected:
                    continue
                try:
                    if path.stat().st_mtime > cutoff:
                        continue
                except OSError:
                    continue
                try:
                    path.unlink()
                    removed += 1
                except OSError:
                    pass
    return removed
