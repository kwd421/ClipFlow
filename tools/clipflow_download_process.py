"""Subprocess entry point for crash-isolated ClipFlow downloads."""

import json
import sys
from pathlib import Path

try:
    from tools import downloader_engine as engine
except ImportError:
    import downloader_engine as engine


def _write_payload(payload, event_path=None):
    line = json.dumps(payload, ensure_ascii=False, default=str) + "\n"
    line = line.encode("utf-8", errors="replace").decode("utf-8")
    if event_path:
        with Path(event_path).open("a", encoding="utf-8", errors="replace") as file:
            file.write(line)
            file.flush()
    stdout_buffer = getattr(sys.stdout, "buffer", None)
    if stdout_buffer is not None:
        stdout_buffer.write(line.encode("utf-8"))
        stdout_buffer.flush()
        return
    sys.stdout.write(line)
    sys.stdout.flush()


def _iter_input_lines(input_stream=None):
    if input_stream is not None:
        yield from input_stream
        return
    stdin_buffer = getattr(sys.stdin, "buffer", None)
    if stdin_buffer is not None:
        for raw_line in stdin_buffer:
            yield raw_line.decode("utf-8", errors="replace")
        return
    yield from sys.stdin


def run_request(request, download_func=engine.download_candidate):
    event_path = request.get("event_path")

    def emit_event(event):
        _write_payload({"type": "event", "event": event}, event_path=event_path)

    result = download_func(
        request.get("page_url") or "",
        request.get("candidate") or {},
        request.get("output_dir") or "",
        cookie_source=request.get("cookie_source") or "없음",
        proxy_url=request.get("proxy_url") or None,
        on_event=emit_event,
    )
    _write_payload({"type": "finished", "result": result}, event_path=event_path)
    return result


def run_persistent(input_stream=None):
    for line in _iter_input_lines(input_stream):
        text = str(line or "").strip()
        if not text:
            continue
        try:
            request = json.loads(text)
        except Exception as exc:
            _write_payload({"type": "failed", "message": f"Cannot read download request: {engine.strip_ansi(exc)}"})
            continue
        try:
            run_request(request)
        except Exception as exc:
            event_path = request.get("event_path") if isinstance(request, dict) else None
            _write_payload({"type": "failed", "message": engine.strip_ansi(exc)}, event_path=event_path)
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "--clipflow-download-worker":
        argv = argv[1:]
    if argv and argv[0] == "--persistent":
        return run_persistent()
    if not argv:
        _write_payload({"type": "failed", "message": "Missing download request path."})
        return 2

    request_path = Path(argv[0])
    try:
        request = json.loads(request_path.read_text(encoding="utf-8"))
    except Exception as exc:
        _write_payload({"type": "failed", "message": f"Cannot read download request: {engine.strip_ansi(exc)}"})
        return 2

    try:
        run_request(request)
        return 0
    except Exception as exc:
        event_path = request.get("event_path") if isinstance(request, dict) else None
        _write_payload({"type": "failed", "message": engine.strip_ansi(exc)}, event_path=event_path)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
