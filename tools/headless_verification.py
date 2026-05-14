import time
import traceback

try:
    from tools import downloader_engine as engine
except ImportError:
    import downloader_engine as engine


VERIFY_TIMEOUT_SECONDS = 180


def run_headless_verification(
    url,
    output_json,
    output_dir,
    cookie_source="없음",
    proxy_url=None,
    output_ext=None,
    should_download=False,
    download_candidate_index=0,
    analyze_func=engine.analyze_url,
    download_func=engine.download_candidate,
):
    started_at = time.time()
    events = []

    def on_event(event):
        events.append(event)

    try:
        analysis = analyze_func(url, cookie_source=cookie_source, proxy_url=proxy_url, output_ext=output_ext, on_event=on_event)
        candidates = analysis.get("candidates") or []
        result = {
            "ok": True,
            "url": url,
            "title": analysis.get("title"),
            "candidate_count": len(candidates),
            "candidates": candidates,
            "warnings": analysis.get("warnings") or [],
            "events": events,
            "download": None,
        }
        if should_download:
            if not candidates:
                raise RuntimeError("No downloadable candidate was found.")
            selected_index = max(0, min(int(download_candidate_index or 0), len(candidates) - 1))
            candidate = candidates[selected_index]
            before_download = time.time()
            download = download_func(
                analysis.get("webpage_url") or url,
                candidate,
                output_dir,
                cookie_source,
                proxy_url=proxy_url,
                on_event=on_event,
            )
            output_extension = (candidate.get("output_ext") or candidate.get("ext") or "mp4").lower()
            newest = engine.newest_file(output_dir, output_extension, since=before_download - 2)
            result["download"] = {
                **download,
                "selected_candidate": candidate,
                "mp4_path": str(newest) if newest else "",
                "mp4_exists": bool(newest and newest.exists()),
            }
            result["ok"] = bool(result["download"]["mp4_exists"])
        result["elapsed_seconds"] = round(time.time() - started_at, 2)
    except Exception as exc:
        result = {
            "ok": False,
            "url": url,
            "error_class": engine.classify_error(str(exc)),
            "error": str(exc),
            "traceback": traceback.format_exc(),
            "events": events,
            "elapsed_seconds": round(time.time() - started_at, 2),
        }

    if output_json:
        engine.write_json(output_json, result)
    return result
