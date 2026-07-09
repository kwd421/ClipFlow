"""Row actions for ClipFlowWindow: open/reveal, select-mode, and deletion.

Provided as a mixin so the large window class stays organized. All methods
operate on ``self`` (the ClipFlowWindow instance) and rely on the module
imports below plus methods that remain on the window class or other mixins.
"""

import shutil
import subprocess
import sys
from pathlib import Path

from PySide6.QtCore import QFile, Qt, QTimer, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QDialog,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
)

try:
    from tools import downloader_engine as engine
    from tools.clipflow_dialogs import DeleteConfirmDialog
    from tools.clipflow_theme import (
        ANALYZING_STATUS,
        COMPLETED_STATUS,
        DOWNLOAD_STATUS,
        PAUSED_STATUS,
        PERMANENT_DELETE_SETTING,
        READY_STATUS,
        WAITING_STATUS,
    )
    from tools.clipflow_widgets import CleanCheckBox
except ImportError:
    import downloader_engine as engine
    from clipflow_dialogs import DeleteConfirmDialog
    from clipflow_theme import (
        ANALYZING_STATUS,
        COMPLETED_STATUS,
        DOWNLOAD_STATUS,
        PAUSED_STATUS,
        PERMANENT_DELETE_SETTING,
        READY_STATUS,
        WAITING_STATUS,
    )
    from clipflow_widgets import CleanCheckBox


def local_file_url(path):
    return QUrl.fromLocalFile(str(Path(path).expanduser().resolve()))


class ActionMixin:
    def open_source_for_row(self, row):
        source_url = row.get("source_url") or ""
        if source_url:
            self.open_url_func(source_url)

    def play_file_for_row(self, row):
        target = self._play_target_for_row(row)
        if target:
            self._open_path(target)
            return
        self._set_status("재생할 파일이 없습니다")

    def _play_target_for_row(self, row):
        if row.get("kind") == "playlist":
            return None
        saved_output = row.get("output_path") or ""
        output_path = Path(saved_output)
        if saved_output and output_path.is_file():
            return output_path
        candidate = self.selected_candidate_for_row_ref(row) or row.get("candidate") or {}
        existing_output = self._existing_output_path_for_row(row, candidate)
        if existing_output and existing_output.is_file():
            return existing_output
        return None

    def open_folder_for_row(self, row):
        reveal_target = None
        open_target = None
        if row.get("kind") == "playlist":
            candidate = row.get("candidate") or {}
            playlist_folder = engine.output_dir_for_candidate(candidate, self.folder_input.text())
            if playlist_folder.exists():
                reveal_target = playlist_folder
            else:
                open_target = self._first_playlist_output_parent(row) or Path(self.folder_input.text()).expanduser()
        else:
            saved_output = row.get("output_path") or ""
            output_path = Path(saved_output)
            if saved_output and output_path.exists():
                reveal_target = output_path
            else:
                candidate = self.selected_candidate_for_row_ref(row) or row.get("candidate") or {}
                existing_output = self._existing_output_path_for_row(row, candidate)
                reveal_target = existing_output if existing_output and existing_output.exists() else None
                open_target = Path(self.folder_input.text()).expanduser()
        if reveal_target:
            self._reveal_in_file_manager(reveal_target)
            return
        open_target = open_target or Path(self.folder_input.text()).expanduser()
        open_target.mkdir(parents=True, exist_ok=True)
        self._open_path(open_target)

    def _reveal_in_file_manager(self, path):
        path = Path(path)
        if sys.platform == "darwin":
            try:
                subprocess.Popen(["open", "-R", str(path)])
                return
            except Exception:
                pass
        if sys.platform.startswith("win") and path.exists():
            try:
                subprocess.Popen(["explorer.exe", f"/select,{path.resolve()}"])
                return
            except Exception:
                pass
        self._open_path(path.parent if path.is_file() else path)

    def _open_path(self, path):
        return QDesktopServices.openUrl(local_file_url(path))

    def row_has_deletable_output(self, row):
        if not row:
            return False
        for path in self._delete_paths_for_row(row, dry_run=True):
            try:
                if path.is_file() and path.stat().st_size > 0:
                    return True
                if path.is_dir() and any(child.is_file() and child.stat().st_size > 0 for child in path.iterdir()):
                    return True
            except OSError:
                continue
        return False

    def _row_analysis_active(self, row):
        if not row:
            return False
        return bool(row.get("analysis_loading") or row.get("child_loading") or row.get("id") == "__analyzing__")

    def _row_matches_running_analysis(self, row):
        thread = getattr(self, "analysis_thread", None)
        if not thread or not thread.isRunning():
            return False
        if self._row_analysis_active(row):
            return True
        analysis_url = str(getattr(self, "_analysis_url", "") or "").strip()
        if not analysis_url:
            return False
        row_urls = {
            str(row.get("analysis_source_url") or "").strip(),
            str(row.get("source_url") or "").strip(),
            str(row.get("input_url") or "").strip(),
        }
        return analysis_url in row_urls

    def _prune_orphan_media_cache(self):
        """Drop disk thumbnails for cards no longer in the list."""
        try:
            from tools.clipflow_cache import prune_thumbnail_cache_for_rows
        except ImportError:
            from clipflow_cache import prune_thumbnail_cache_for_rows
        try:
            prune_thumbnail_cache_for_rows(getattr(self, "rows", None) or [])
        except Exception:
            pass

    def remove_row(self, row):
        if row.get("kind") == "playlist":
            if self._playlist_remove_blocked(row):
                return
            self._remove_playlist_row(row)
            return
        if row.get("status") in {"분석 중", DOWNLOAD_STATUS}:
            return
        if self._row_analysis_active(row) or self._row_matches_running_analysis(row):
            cancel = getattr(self, "_cancel_active_analysis", None)
            if callable(cancel):
                cancel()
        if row in self.rows:
            index = self.rows.index(row)
            transfer_hover = self._row_has_hover(row)
            self.rows.pop(index)
            if self.selected_row_index >= len(self.rows):
                self.selected_row_index = len(self.rows) - 1
            self._render_rows()
            if transfer_hover:
                self._queue_hover_row_at_index(index)
            self._save_completed_history()
            self._prune_orphan_media_cache()

    def _playlist_is_paused(self, row):
        if not row:
            return False
        return row.get("status") == PAUSED_STATUS or bool(
            row.get("_playlist_analysis_resume") and row.get("_playlist_auto_download_paused")
        )

    def _playlist_remove_blocked(self, row):
        """Match single-video: no remove/delete while download or analysis is running.

        User must pause first. Paused (or idle/completed) playlists may be removed.
        """
        if not row or row.get("kind") != "playlist":
            return False
        if self._playlist_is_paused(row):
            return False
        status = row.get("status")
        if status in {DOWNLOAD_STATUS, ANALYZING_STATUS, "분석 중"}:
            return True
        if row.get("analysis_loading"):
            return True
        # Block if any child is actively downloading (parent status can lag).
        return any(
            child.get("status") == DOWNLOAD_STATUS
            for child in self._playlist_children_for_parent(row)
        )

    def _remove_playlist_row(self, row):
        """Remove playlist cards from the list only (does not delete files).

        File deletion is handled by delete_file_for_row / bulk file delete.
        Callers must ensure remove is not blocked (pause first while downloading).
        """
        if not row or row not in self.rows:
            return
        if self._playlist_remove_blocked(row):
            return
        children = [
            child
            for child in self._playlist_children_for_parent(row)
            if not child.get("child_loading")
        ]
        completed = [child for child in children if child.get("status") == COMPLETED_STATUS]
        incomplete = [child for child in children if child.get("status") != COMPLETED_STATUS]
        analyzing = bool(
            row.get("analysis_loading")
            or row.get("_playlist_analysis_resume")
            or (getattr(self, "analysis_thread", None) and self.analysis_thread.isRunning())
        )
        choice = "all"
        if completed and (incomplete or analyzing):
            choice = self._confirm_playlist_remove_choice(len(completed), len(incomplete), analyzing)
            if choice is None:
                return
        else:
            # Empty / incomplete-only / completed-only: drop matching cards from the list.
            choice = "all"

        # Stop analysis / downloads so work does not continue after cards are gone.
        parent_id = row.get("id")
        for child in list(self._playlist_children_for_parent(row)):
            if child.get("status") in {DOWNLOAD_STATUS, WAITING_STATUS} or child in getattr(self, "queued_download_rows", []):
                if hasattr(self, "pause_download_for_row"):
                    self.pause_download_for_row(child)
            if child in getattr(self, "queued_download_rows", []):
                self.queued_download_rows = [item for item in self.queued_download_rows if item is not child]
            active = getattr(self, "active_downloads", None)
            if isinstance(active, list):
                self.active_downloads = [item for item in active if item.get("row") is not child]

        self._analysis_discard_result = True
        self._analysis_auto_download = False
        if getattr(self, "_playlist_event_parent_id", "") == parent_id:
            self._playlist_event_parent_id = ""
        if analyzing or row.get("_playlist_analysis_resume") or (
            getattr(self, "analysis_thread", None) and self.analysis_thread.isRunning()
        ):
            stop = getattr(self, "_stop_analysis_worker", None)
            if callable(stop):
                stop()
            else:
                cancel = getattr(self, "_cancel_active_analysis", None)
                if callable(cancel):
                    cancel()

        index = self.rows.index(row) if row in self.rows else -1
        transfer_hover = self._row_has_hover(row)
        if choice == "keep_completed":
            others = [
                item
                for item in self.rows
                if item is not row and item.get("parent_playlist_id") != parent_id
            ]
            for child in completed:
                child["parent_playlist_id"] = parent_id
                child["is_playlist_child"] = True
            row.pop("_playlist_analysis_resume", None)
            row.pop("_playlist_auto_download_paused", None)
            row["analysis_loading"] = False
            self.rows = [row] + completed + others
            self._refresh_playlist_parent_metadata(row)
            self._refresh_playlist_parent_status(row)
            if completed and all(child.get("status") == COMPLETED_STATUS for child in completed):
                row["status"] = COMPLETED_STATUS
                row["status_detail"] = f"{len(completed)}/{len(completed)}"
                row["progress"] = 100
                row["progress_text"] = ""
            else:
                row["status"] = READY_STATUS
        else:
            # Full list wipe: parent + every child (including loading placeholders).
            # Files on disk are left intact — use the trash action to delete files.
            self.rows = [
                item
                for item in self.rows
                if item is not row and item.get("parent_playlist_id") != parent_id
            ]
            row.pop("_playlist_analysis_resume", None)
            row.pop("_playlist_auto_download_paused", None)
            row["analysis_loading"] = False

        if self.selected_row_index >= len(self.rows):
            self.selected_row_index = len(self.rows) - 1
        self._render_rows()
        if transfer_hover and index >= 0:
            self._queue_hover_row_at_index(min(index, max(0, len(self.rows) - 1)))
        self._save_completed_history()
        self._prune_orphan_media_cache()

    def _confirm_playlist_remove_choice(self, completed_count, incomplete_count, analyzing):
        """Return list-remove choice or None if cancelled. Does not delete files."""
        dialog = DeleteConfirmDialog.for_playlist_remove(
            completed_count=completed_count,
            incomplete_count=incomplete_count,
            analyzing=analyzing,
            parent=self,
        )
        if dialog.exec() != QDialog.Accepted:
            return None
        # Map shared dialog choices: alt = keep completed, ok = wipe all from list.
        if dialog.result_choice == "alt":
            return "keep_completed"
        if dialog.result_choice == "ok":
            return "all"
        return None

    def _row_has_hover(self, row):
        widget = row.get("widget") if isinstance(row, dict) else None
        if not widget:
            return False
        return widget.property("hovered") == "true" or widget.underMouse()

    def _queue_hover_row_at_index(self, index):
        QTimer.singleShot(0, lambda: self._hover_row_at_index(index))

    def _hover_row_at_index(self, index):
        if index < 0 or not self.rows:
            return
        target_index = min(index, len(self.rows) - 1)
        for row_index, row in enumerate(self.rows):
            widget = row.get("widget")
            if widget:
                widget._set_hovered(row_index == target_index and widget.isVisible())

    def _toggle_select_mode(self, *_args):
        self.select_mode = not getattr(self, "select_mode", False)
        self.select_toggle.setText("")
        self.select_toggle.setProperty("active", "true" if self.select_mode else "false")
        if hasattr(self, "_refresh_select_toggle_icon"):
            self._refresh_select_toggle_icon()
        self.select_toggle.style().unpolish(self.select_toggle)
        self.select_toggle.style().polish(self.select_toggle)
        self.select_actions.setVisible(self.select_mode)
        if not self.select_mode:
            for row in self.rows:
                row["checked"] = False
        for row in self.rows:
            widget = row.get("widget")
            if widget:
                widget.set_select_mode(self.select_mode)

    def on_row_check_changed(self):
        return

    def _select_all_rows(self):
        for row in self.rows:
            row["checked"] = bool(self._row_is_visible(row))
            widget = row.get("widget")
            if widget:
                widget.set_select_mode(True)

    def _delete_selected_from_list(self):
        self._remove_selected(delete_files=False)

    def _delete_selected_files(self):
        self._remove_selected(delete_files=True)

    def _remove_selected(self, delete_files):
        removable = [
            row
            for row in self.rows
            if row.get("checked") and row.get("status") not in {"분석 중", DOWNLOAD_STATUS}
        ]
        if not removable:
            self._set_status("선택된 항목이 없습니다")
            return
        if not self._confirm_selected(len(removable), delete_files):
            return
        if delete_files:
            for row in removable:
                self._delete_paths_for_row(row)
        removed_indexes = [self.rows.index(row) for row in removable if row in self.rows]
        transfer_hover = any(self._row_has_hover(row) for row in removable)
        keep = [row for row in self.rows if row not in removable]
        self.rows = keep
        if self.selected_row_index >= len(self.rows):
            self.selected_row_index = len(self.rows) - 1
        self._render_rows()
        if transfer_hover and removed_indexes:
            self._queue_hover_row_at_index(min(removed_indexes))
        self._save_completed_history()
        self._prune_orphan_media_cache()

    def _settings_bool(self, key, default=False):
        settings = getattr(self, "settings", None)
        if settings is None:
            return bool(default)
        value = settings.value(key, default)
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value != 0
        text = str(value or "").strip().lower()
        if text in {"1", "true", "yes", "on"}:
            return True
        if text in {"0", "false", "no", "off", ""}:
            return False
        return bool(default)

    def _permanent_delete_enabled(self):
        return self._settings_bool(PERMANENT_DELETE_SETTING, False)

    def _set_permanent_delete_enabled(self, enabled):
        settings = getattr(self, "settings", None)
        if settings is None:
            return
        settings.setValue(PERMANENT_DELETE_SETTING, bool(enabled))
        settings.sync()

    def _confirm_selected(self, count, delete_files):
        dialog = DeleteConfirmDialog.for_bulk_selection(
            count,
            delete_files=delete_files,
            permanent_delete=self._permanent_delete_enabled() if delete_files else False,
            on_permanent_delete_changed=self._set_permanent_delete_enabled if delete_files else None,
            parent=self,
        )
        accepted = dialog.exec() == QDialog.Accepted
        if accepted and delete_files:
            self._set_permanent_delete_enabled(dialog.permanent_delete_enabled())
        return accepted

    def delete_file_for_row(self, row):
        if row.get("status") == DOWNLOAD_STATUS:
            return
        if row.get("kind") == "playlist" and self._playlist_remove_blocked(row):
            return
        output_path = self._delete_target_for_row(row)
        delete_paths = self._delete_paths_for_row(row, dry_run=True)
        if output_path is None and not delete_paths:
            if row.get("status") == PAUSED_STATUS or self._row_analysis_active(row):
                self.remove_row(row)
            return
        confirm_target = output_path or (delete_paths[0] if delete_paths else None)
        confirmed = (
            self.confirm_delete_func(confirm_target)
            if self.confirm_delete_func
            else self._confirm_file_delete(confirm_target, row)
        )
        if not confirmed:
            return
        permanent = self._permanent_delete_enabled()
        try:
            # One path for single rows and playlist parents (partials + folder cleanup).
            self._delete_paths_for_row(row, permanent=permanent)
        except OSError as exc:
            QMessageBox.warning(self, "파일 삭제 실패", str(exc))
            return
        self._remove_rows_after_file_delete(row)
        self._save_completed_history()

    def _delete_target_for_row(self, row):
        if row.get("kind") == "playlist":
            return engine.output_dir_for_candidate(row.get("candidate") or {}, self.folder_input.text())
        saved_output = row.get("output_path") or ""
        if saved_output:
            return Path(saved_output)
        candidate = self.selected_candidate_for_row_ref(row) or row.get("candidate") or {}
        return self._existing_output_path_for_row(row, candidate)

    def _delete_filesystem_path(self, path, permanent=False):
        path = Path(path).expanduser()
        if not path.exists():
            return False
        if permanent:
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
            return True
        if QFile.moveToTrash(str(path)):
            return True
        # Trash unavailable/failed: fall back to permanent delete so Yes still works.
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
        return True

    def _save_folder_path(self):
        return Path(self.folder_input.text()).expanduser()

    def _is_save_folder_path(self, path):
        """True when path is the user save root — never wipe this directory wholesale."""
        try:
            return Path(path).expanduser().resolve() == self._save_folder_path().resolve()
        except OSError:
            try:
                return Path(path).expanduser() == self._save_folder_path()
            except OSError:
                return False

    def _delete_paths_for_row(self, row, dry_run=False, permanent=None):
        paths = self._download_output_paths_for_row(row)
        if dry_run:
            return [path for path in paths if path.exists()]
        if permanent is None:
            permanent = self._permanent_delete_enabled()
        deleted = []
        # Delete files first, directories last. Never delete the save root itself.
        ordered = sorted(
            (path for path in paths if not self._is_save_folder_path(path)),
            key=lambda path: (path.is_dir() if path.exists() else False, str(path)),
        )
        for path in ordered:
            if not path.exists():
                continue
            try:
                self._delete_filesystem_path(path, permanent=permanent)
                deleted.append(path)
            except OSError:
                pass
        # Full playlist wipe: clear leftovers only inside a dedicated playlist subfolder.
        if row.get("kind") == "playlist":
            playlist_dir = engine.output_dir_for_candidate(row.get("candidate") or {}, self.folder_input.text())
            playlist_dir = Path(playlist_dir).expanduser()
            try:
                if playlist_dir.exists() and not self._is_save_folder_path(playlist_dir):
                    for leftover in list(playlist_dir.iterdir()):
                        try:
                            self._delete_filesystem_path(leftover, permanent=permanent)
                        except OSError:
                            pass
                    if playlist_dir.exists():
                        self._delete_filesystem_path(playlist_dir, permanent=permanent)
            except OSError:
                pass
        return deleted

    def _download_output_paths_for_row(self, row):
        if row.get("kind") == "playlist":
            playlist_dir = Path(
                engine.output_dir_for_candidate(row.get("candidate") or {}, self.folder_input.text())
            ).expanduser()
            paths = []
            for child in self._playlist_children_for_parent(row):
                paths.extend(self._download_output_paths_for_row(child))
            # Only sweep every file when the playlist has its own subfolder.
            # If playlist_dir is the save root (same name), only child-mapped paths are safe.
            if playlist_dir.is_dir() and not self._is_save_folder_path(playlist_dir):
                try:
                    paths.extend(path for path in playlist_dir.iterdir() if path.is_file())
                except OSError:
                    pass
                paths.append(playlist_dir)
            return list(dict.fromkeys(paths))

        candidate = self.selected_candidate_for_row_ref(row) or row.get("candidate") or {}
        output_dir = self._output_dir_for_row(row, candidate) if hasattr(self, "_output_dir_for_row") else engine.output_dir_for_candidate(candidate, self.folder_input.text())
        output_dir = Path(output_dir).expanduser()
        paths = []
        saved_output = row.get("output_path") or ""
        if saved_output:
            paths.append(Path(saved_output).expanduser())
        final_path = engine.final_output_path_for_candidate(candidate, output_dir)
        if final_path:
            paths.append(Path(final_path).expanduser())
            if output_dir.exists():
                final_name = final_path.name
                stem = Path(final_name).stem
                try:
                    for path in output_dir.iterdir():
                        name = path.name
                        if name == final_name or name.startswith(final_name) or name.startswith(stem + "."):
                            paths.append(path)
                except OSError:
                    pass
        return list(dict.fromkeys(paths))

    def _remove_rows_after_file_delete(self, row):
        index = self.rows.index(row) if row in self.rows else -1
        transfer_hover = self._row_has_hover(row)
        if row.get("kind") == "playlist":
            parent_id = row.get("id")
            self.rows = [
                item for item in self.rows
                if item is not row and item.get("parent_playlist_id") != parent_id
            ]
        else:
            parent = self._parent_playlist_for_child(row)
            if row in self.rows:
                self.rows.remove(row)
            if parent:
                parent["playlist_entries"] = [
                    {"candidate": child.get("candidate") or {}, "qualities": child.get("qualities") or []}
                    for child in self._playlist_children_for_parent(parent)
                    if child is not row and not child.get("child_loading")
                ]
                self._refresh_playlist_parent_status(parent)
        if self.selected_row_index >= len(self.rows):
            self.selected_row_index = len(self.rows) - 1
        self._render_rows()
        if transfer_hover:
            self._queue_hover_row_at_index(index)
        self._prune_orphan_media_cache()

    def _create_delete_confirm_dialog(self, output_path, row=None):
        permanent = self._permanent_delete_enabled()
        if row and row.get("kind") == "playlist":
            child_count = len([
                child
                for child in self._playlist_children_for_parent(row)
                if not child.get("child_loading")
            ])
            return DeleteConfirmDialog(
                output_path,
                self,
                title_text="재생목록을 삭제하시겠습니까?",
                detail_text=f"{output_path}\n하위 파일 {child_count}개도 함께 삭제됩니다.",
                window_title="재생목록 삭제",
                permanent_delete=permanent,
                on_permanent_delete_changed=self._set_permanent_delete_enabled,
            )
        return DeleteConfirmDialog(
            output_path,
            self,
            permanent_delete=permanent,
            on_permanent_delete_changed=self._set_permanent_delete_enabled,
        )

    def _confirm_file_delete(self, output_path, row=None):
        dialog = self._create_delete_confirm_dialog(output_path, row)
        accepted = dialog.exec() == QDialog.Accepted
        self._set_permanent_delete_enabled(dialog.permanent_delete_enabled())
        return accepted
