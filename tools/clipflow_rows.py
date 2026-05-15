# Auto-split from clipflow_qt.py; keep behavior changes in focused commits.
from pathlib import Path
from urllib.parse import urlparse

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QProgressBar,
    QSizePolicy,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

try:
    from tools import downloader_engine as engine
    from tools.clipflow_icons import LucideIconButton, LucideIconWidget
    from tools.clipflow_theme import (
        ACTIONS_WIDTH,
        DURATION_WIDTH,
        MEDIA_MIN_WIDTH,
        ROW_COLUMN_SPACING,
        SIZE_WIDTH,
        THUMBNAIL_WIDTH,
    )
    from tools.clipflow_widgets import ThumbnailPlaceholder
except ImportError:
    import downloader_engine as engine
    from clipflow_icons import LucideIconButton, LucideIconWidget
    from clipflow_theme import (
        ACTIONS_WIDTH,
        DURATION_WIDTH,
        MEDIA_MIN_WIDTH,
        ROW_COLUMN_SPACING,
        SIZE_WIDTH,
        THUMBNAIL_WIDTH,
    )
    from clipflow_widgets import ThumbnailPlaceholder


ACTIVE_STATUSES = {"분석 중", "다운로드 중"}
COMPLETED_STATUS = "완료"
ERROR_STATUS = "오류"


def source_domain(url):
    host = urlparse(url or "").netloc.lower()
    if host.startswith("www."):
        host = host[4:]
    return host or "source"


def row_source_url(analysis, candidate):
    return (
        candidate.get("webpage_url")
        or candidate.get("page_url")
        or candidate.get("source_url")
        or candidate.get("source")
        or candidate.get("url")
        or (analysis or {}).get("webpage_url")
        or (analysis or {}).get("url")
        or ""
    )


def row_kind(candidate):
    media_type = str(candidate.get("media_type") or "video").lower()
    if media_type in {"image", "gallery", "playlist"}:
        return media_type
    return "video"


def row_info_text(candidate):
    kind = row_kind(candidate)
    if kind == "gallery":
        count = engine.safe_int(candidate.get("item_count") or candidate.get("image_count"))
        return f"{count}장" if count else "이미지 묶음"
    if kind == "image":
        return "1장"
    if kind == "playlist":
        count = engine.safe_int(candidate.get("item_count") or candidate.get("playlist_count"))
        return f"영상 {count}개" if count else "재생목록"
    seconds = engine.safe_int(candidate.get("duration"))
    if seconds:
        hours = seconds // 3600
        minutes = (seconds % 3600) // 60
        remaining = seconds % 60
        return f"{hours:02d}:{minutes:02d}:{remaining:02d}"
    return engine.display_duration(candidate.get("duration"))


def quality_display_label(candidate):
    if candidate.get("media_type") == "audio":
        return "오디오"
    resolution = str(candidate.get("resolution") or "").strip()
    if resolution and resolution.lower() != "unknown":
        if "x" in resolution:
            height = resolution.split("x")[-1]
            return f"{height}p" if height.isdigit() else resolution
        return resolution
    height = engine.safe_int(candidate.get("height"))
    return f"{height}p" if height else "원본"


def format_display_label(candidate):
    return str(candidate.get("output_ext") or candidate.get("ext") or "").upper() or "MP4"


def format_sort_rank(label):
    normalized = str(label or "").upper()
    ranks = {"MP4": 0, "WEBM": 1, "MP3": 2, "WAV": 3, "AAC": 4}
    return ranks.get(normalized, 10)


def candidate_size_value(candidate):
    return (
        engine.safe_int(candidate.get("sort_bytes"))
        or engine.safe_int(candidate.get("filesize"))
        or engine.safe_int(candidate.get("filesize_approx"))
        or 0
    )


def build_quality_options(qualities):
    grouped = {}
    for candidate in qualities or []:
        quality_label = quality_display_label(candidate)
        format_label = format_display_label(candidate)
        option = grouped.setdefault(quality_label, {"label": quality_label, "formats": {}})
        existing = option["formats"].get(format_label)
        if not existing or candidate_size_value(candidate) > candidate_size_value(existing):
            option["formats"][format_label] = candidate

    options = []
    for option in grouped.values():
        formats = [
            {"label": label, "candidate": candidate}
            for label, candidate in option["formats"].items()
        ]
        formats.sort(key=lambda item: (format_sort_rank(item["label"]), -candidate_size_value(item["candidate"])))
        options.append({"label": option["label"], "formats": formats})
    return options


class DownloadRowWidget(QFrame):
    def __init__(self, owner, row):
        super().__init__()
        self.owner = owner
        self.row = row
        self.setObjectName("DownloadRow")
        self.setProperty("selected", "false")
        self.setProperty("hovered", "false")
        self.setCursor(Qt.PointingHandCursor)
        self.setMouseTracking(True)
        self.setMinimumHeight(72)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self._build()
        self.refresh()

    def _build(self):
        outer = QHBoxLayout(self)
        outer.setContentsMargins(12, 6, 12, 6)
        outer.setSpacing(ROW_COLUMN_SPACING)

        self.thumbnail = ThumbnailPlaceholder()
        outer.addWidget(self.thumbnail, 0, Qt.AlignVCenter)

        self.item_widget = QWidget()
        self.item_widget.setMinimumWidth(MEDIA_MIN_WIDTH - THUMBNAIL_WIDTH - ROW_COLUMN_SPACING)
        self.item_widget.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        item_area = QVBoxLayout(self.item_widget)
        item_area.setContentsMargins(0, 0, 0, 0)
        item_area.setSpacing(3)

        self.title_label = QLabel()
        self.title_label.setObjectName("RowTitle")
        self.title_label.setWordWrap(False)
        self.title_label.setTextInteractionFlags(Qt.NoTextInteraction)
        item_area.addWidget(self.title_label)

        source_line = QHBoxLayout()
        source_line.setSpacing(6)
        self.site_button = LucideIconButton("play", size=18, icon_size=12)
        self.site_button.setObjectName("SourceButton")
        self.site_button.setFixedSize(18, 18)
        self.site_button.clicked.connect(self._open_source)
        source_line.addWidget(self.site_button)

        self.domain_label = QToolButton()
        self.domain_label.setObjectName("SourceTextButton")
        self.domain_label.setCursor(Qt.PointingHandCursor)
        self.domain_label.clicked.connect(self._open_source)
        source_line.addWidget(self.domain_label)
        source_line.addStretch(1)
        item_area.addLayout(source_line)

        progress_line = QHBoxLayout()
        progress_line.setContentsMargins(0, 0, 0, 0)
        progress_line.setSpacing(8)
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.setTextVisible(False)
        self.progress_bar.setFixedHeight(4)
        self.progress_bar.setMaximumWidth(220)
        self.progress_bar.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.progress_text = QLabel("")
        self.progress_text.setObjectName("MetaText")
        progress_line.addWidget(self.progress_bar, 1)
        progress_line.addWidget(self.progress_text, 0)
        item_area.addLayout(progress_line)

        outer.addWidget(self.item_widget, 1)

        self.info_widget = QWidget()
        self.info_widget.setFixedWidth(DURATION_WIDTH)
        info_layout = QHBoxLayout(self.info_widget)
        info_layout.setContentsMargins(0, 0, 0, 0)
        info_layout.setSpacing(4)
        info_layout.setAlignment(Qt.AlignCenter)
        self.info_icon = LucideIconWidget("clock", size=18)
        info_layout.addWidget(self.info_icon)
        self.info_label = QLabel()
        self.info_label.setObjectName("MetaText")
        self.info_label.setAlignment(Qt.AlignCenter)
        info_layout.addWidget(self.info_label)
        outer.addWidget(self.info_widget, 0, Qt.AlignVCenter)

        self.size_widget = QWidget()
        self.size_widget.setFixedWidth(SIZE_WIDTH)
        size_layout = QHBoxLayout(self.size_widget)
        size_layout.setContentsMargins(0, 0, 0, 0)
        size_layout.setSpacing(4)
        size_layout.setAlignment(Qt.AlignCenter)
        self.size_icon = LucideIconWidget("file-text", size=18)
        size_layout.addWidget(self.size_icon)
        self.size_label = QLabel()
        self.size_label.setAlignment(Qt.AlignCenter)
        size_layout.addWidget(self.size_label)
        outer.addWidget(self.size_widget, 0, Qt.AlignVCenter)

        self.actions_widget = QFrame(self)
        self.actions_widget.setObjectName("ActionOverlay")
        self.actions_widget.setFixedWidth(ACTIONS_WIDTH + 68)
        actions = QHBoxLayout(self.actions_widget)
        actions.setContentsMargins(8, 0, 8, 0)
        actions.setSpacing(4)
        actions.setAlignment(Qt.AlignCenter)

        self.open_source_button = LucideIconButton("link")
        self.open_source_button.setToolTip("원본 열기")
        self.open_source_button.clicked.connect(self._open_source)
        actions.addWidget(self.open_source_button)

        self.open_folder_button = LucideIconButton("folder")
        self.open_folder_button.setToolTip("폴더 열기")
        self.open_folder_button.clicked.connect(self._open_folder)
        actions.addWidget(self.open_folder_button)

        self.remove_button = LucideIconButton("x")
        self.remove_button.setToolTip("목록에서 삭제")
        self.remove_button.clicked.connect(self._remove_row)
        actions.addWidget(self.remove_button)

        self.delete_file_button = LucideIconButton("trash-2", danger=True)
        self.delete_file_button.setToolTip("파일 삭제")
        self.delete_file_button.clicked.connect(self._delete_file)
        actions.addWidget(self.delete_file_button)

        self.more_button = LucideIconButton("more-vertical")
        self.more_button.setToolTip("더보기")
        actions.addWidget(self.more_button)
        self.actions_widget.hide()

    def mousePressEvent(self, event):
        self.owner.select_row_for_widget(self)
        super().mousePressEvent(event)

    def enterEvent(self, event):
        self._set_hovered(True)
        super().enterEvent(event)

    def leaveEvent(self, event):
        self._set_hovered(False)
        super().leaveEvent(event)

    def resizeEvent(self, event):
        self._position_actions()
        super().resizeEvent(event)

    def refresh(self):
        candidate = self.owner.selected_candidate_for_row_ref(self.row) or self.row["candidate"]
        title = candidate.get("display_title") or candidate.get("title") or "media"
        self.title_label.setText(str(title))
        self.title_label.setToolTip(str(title))
        self.info_label.setText(row_info_text(candidate))
        self.size_label.setText(engine.display_size(candidate_size_value(candidate)))
        self.thumbnail.set_thumbnail_url(candidate.get("thumbnail") or "", self.row.get("source_url") or "")
        self._refresh_source_button()
        self.set_status(self.row.get("status") or "준비", self.row.get("status_detail") or "")
        self.set_progress(self.row.get("progress") or 0, self.row.get("progress_text") or "")
        self._refresh_actions()

    def _refresh_source_button(self):
        source_url = self.row.get("source_url") or ""
        domain = source_domain(source_url)
        tooltip = f"{domain}\n원본 링크 열기"
        self.domain_label.setText(domain)
        self.domain_label.setToolTip(tooltip)
        self.site_button.setToolTip(tooltip)
        self.site_button.setEnabled(bool(source_url))
        self.domain_label.setEnabled(bool(source_url))
        self.open_source_button.setEnabled(bool(source_url))

    def _refresh_actions(self):
        active = self.row.get("status") in ACTIVE_STATUSES
        completed = self.row.get("status") == COMPLETED_STATUS
        output_path = Path(self.row.get("output_path") or "")
        has_output = bool(self.row.get("output_path")) and output_path.exists()
        self.open_source_button.setEnabled(completed and bool(self.row.get("source_url")))
        self.open_folder_button.setEnabled(completed and not active)
        self.remove_button.setEnabled(completed and not active)
        self.delete_file_button.setEnabled(completed and has_output and not active)
        self.more_button.setEnabled(completed)

    def _position_actions(self):
        width = self.actions_widget.width()
        self.actions_widget.setGeometry(max(0, self.width() - width - 6), 0, width, self.height())
        self.actions_widget.raise_()

    def _set_hovered(self, hovered):
        self.setProperty("hovered", "true" if hovered else "false")
        show_actions = hovered and self.row.get("status") == COMPLETED_STATUS
        self.actions_widget.setVisible(show_actions)
        if show_actions:
            self._position_actions()
        self._refresh_actions()
        self._repolish()

    def _open_source(self):
        self.owner.open_source_for_row(self.row)

    def _open_folder(self):
        self.owner.open_folder_for_row(self.row)

    def _remove_row(self):
        self.owner.remove_row(self.row)

    def _delete_file(self):
        self.owner.delete_file_for_row(self.row)

    def set_selected(self, selected):
        self.setProperty("selected", "true" if selected else "false")
        self._repolish()

    def _repolish(self):
        self.style().unpolish(self)
        self.style().polish(self)
        self.update()

    def set_status(self, status, detail=""):
        self.row["status"] = status
        self.row["status_detail"] = detail
        self._refresh_actions()
        self.set_progress(self.row.get("progress") or 0, self.row.get("progress_text") or "")

    def set_progress(self, value, text=""):
        bounded = max(0, min(100, int(float(value or 0))))
        self.row["progress"] = bounded
        status = self.row.get("status")
        active = status in ACTIVE_STATUSES
        error_detail = status == ERROR_STATUS and self.row.get("status_detail")
        display_text = text if active else (self.row.get("status_detail") if error_detail else "")
        self.row["progress_text"] = display_text
        self.progress_bar.setValue(bounded)
        show_progress = active and (bool(display_text) or bounded > 0)
        self.progress_bar.setVisible(show_progress)
        self.progress_text.setVisible(bool(display_text))
        self.progress_text.setText(display_text)
