# ClipFlow PySide6 Migration

This branch keeps the existing downloader behavior intact while adding a PySide6 MVP named ClipFlow.

## Entrypoints

- Legacy Tkinter app: `python tools/url_downloader_gui.py`
- PySide6 ClipFlow MVP: `python tools/clipflow_qt.py`
- Headless verification helper: `tools/headless_verification.py`

The existing Windows PyInstaller spec still targets the legacy Tkinter entrypoint. PySide6 packaging is intentionally left for a later PR.

## Shared Core

- `tools/downloader_engine.py` remains the analyzer/downloader engine.
- `tools/candidate_presenter.py` owns GUI-independent candidate grouping and quality label formatting.
- `tools/headless_verification.py` owns GUI-independent verification flow for URL analysis and optional download checks.
- Tkinter and PySide6 call the shared presenter/engine modules instead of duplicating candidate logic.

This split is meant to make UI changes cheap: future ClipFlow layout changes should mostly touch `tools/clipflow_qt.py`, while candidate grouping and download behavior stay in shared modules.

## ClipFlow MVP Status

Implemented:

- App title `ClipFlow`
- URL input with a single primary button: paste, analyze, download
- Save folder picker
- Cookie selector with existing choices: none, Chrome, Edge, Firefox
- QThread-based analysis worker
- QThread-based download worker
- Download-manager style row list with columns for media, quality, format, duration, size, status, and actions
- Per-row quality and format dropdowns
- Completed rows switch quality and format controls to fixed text
- Selected quality and format map to the underlying candidate passed to download
- Row-local status pills, progress indicators, completion checks, and error details
- Unified action buttons for opening folders, removing rows, deleting completed files, and future row actions
- Placeholder thumbnails and source link buttons

## Current Limitations

- Real thumbnail and site-icon loading are not implemented in the PySide6 UI yet.
- PySide6 UI polish is improved but still not the final visual design.
- PySide6 PyInstaller packaging is not added in this pass.
- The legacy Tkinter Windows build path is preserved as the packaged app path.
- No DRM, CAPTCHA, paid/private content, age-restricted-content, login bypass, or new site-specific circumvention behavior was added.

## Recommended Next PR

Recommended next PR: `codex/clipflow-qt-polish-and-packaging`

Suggested scope:

- Add safe async thumbnail and site-icon loading with placeholders and timeouts.
- Add playlist/gallery row expansion only through generic shared models.
- Add a PySide6-specific PyInstaller spec after launch and workflow tests are stable.
- Add a small manual QA checklist for Windows and macOS.
