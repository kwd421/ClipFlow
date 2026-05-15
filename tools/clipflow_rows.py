# Auto-split from clipflow_qt.py; keep behavior changes in focused commits.
from pathlib import Path
from urllib.parse import urlparse

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QFrame, QHBoxLayout, QLabel, QProgressBar, QSizePolicy, QToolButton, QVBoxLayout, QWidget

try:
    from tools import downloader_engine as engine
    from tools.clipflow_theme import (
        ACTIONS_WIDTH, DURATION_WIDTH, FORMAT_WIDTH, MEDIA_MIN_WIDTH, QUALITY_WIDTH, ROW_COLUMN_SPACING,
        SIZE_WIDTH, STATUS_STYLES, STATUS_WIDTH, THUMBNAIL_WIDTH,
    )
    from tools.clipflow_widgets import ActionIconButton, CleanComboBox, LineIcon, ThumbnailBox
except ImportError:
    import downloader_engine as engine
    from clipflow_theme import (
        ACTIONS_WIDTH, DURATION_WIDTH, FORMAT_WIDTH, MEDIA_MIN_WIDTH, QUALITY_WIDTH, ROW_COLUMN_SPACING,
        SIZE_WIDTH, STATUS_STYLES, STATUS_WIDTH, THUMBNAIL_WIDTH,
    )
    from clipflow_widgets import ActionIconButton, CleanComboBox, LineIcon, ThumbnailBox

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
    ranks = {"MP4": 0, "WEBM": 1, "WAV": 2}
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
        self.setMinimumHeight(70)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self._build()
        self.refresh()

    def _build(self):
        outer = QHBoxLayout(self)
        outer.setContentsMargins(12, 5, 12, 5)
        outer.setSpacing(ROW_COLUMN_SPACING)

        self.thumbnail = ThumbnailBox()
        outer.addWidget(self.thumbnail)

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
        self.site_button = QToolButton()
        self.site_button.setObjectName("SourceButton")
        self.site_button.setText("▶")
        self.site_button.setFixedSize(18, 18)
        self.site_button.clicked.connect(self._open_source)
        source_line.addWidget(self.site_button)

        self.domain_label = QLabel("")
        self.domain_label.setObjectName("MetaText")
        source_line.addWidget(self.domain_label)
        source_line.addStretch(1)
        item_area.addLayout(source_line)

        outer.addWidget(self.item_widget, 1)

        self.quality_combo = CleanComboBox()
        self.quality_combo.setFixedWidth(QUALITY_WIDTH)
        self.quality_combo.currentIndexChanged.connect(self._quality_changed)
        outer.addWidget(self.quality_combo)

        self.quality_value_label = QLabel()
        self.quality_value_label.setObjectName("QualityValue")
        self.quality_value_label.setFixedWidth(QUALITY_WIDTH)
        self.quality_value_label.setMaximumHeight(30)
        self.quality_value_label.setAlignment(Qt.AlignCenter)
        outer.addWidget(self.quality_value_label)

        self.format_combo = CleanComboBox()
        self.format_combo.setFixedWidth(FORMAT_WIDTH)
        self.format_combo.currentIndexChanged.connect(self._format_changed)
        outer.addWidget(self.format_combo)

        self.format_label = QLabel()
        self.format_label.setObjectName("FormatValue")
        self.format_label.setFixedWidth(FORMAT_WIDTH)
        self.format_label.setMaximumHeight(30)
        self.format_label.setAlignment(Qt.AlignCenter)
        outer.addWidget(self.format_label)

        self.info_widget = QWidget()
        self.info_widget.setFixedWidth(DURATION_WIDTH)
        info_layout = QHBoxLayout(self.info_widget)
        info_layout.setContentsMargins(0, 0, 0, 0)
        info_layout.setSpacing(4)
        info_layout.setAlignment(Qt.AlignCenter)
        self.info_icon = LineIcon("clock")
        self.info_icon.setFixedSize(18, 18)
        info_layout.addWidget(self.info_icon)
        self.info_label = QLabel()
        self.info_label.setObjectName("MetaText")
        self.info_label.setAlignment(Qt.AlignCenter)
        info_layout.addWidget(self.info_label)
        outer.addWidget(self.info_widget)

        self.size_widget = QWidget()
        self.size_widget.setFixedWidth(SIZE_WIDTH)
        size_layout = QHBoxLayout(self.size_widget)
        size_layout.setContentsMargins(0, 0, 0, 0)
        size_layout.setSpacing(4)
        size_layout.setAlignment(Qt.AlignCenter)
        self.size_icon = LineIcon("file")
        self.size_icon.setFixedSize(18, 18)
        size_layout.addWidget(self.size_icon)
        self.size_label = QLabel()
        self.size_label.setAlignment(Qt.AlignCenter)
        size_layout.addWidget(self.size_label)
        outer.addWidget(self.size_widget)

        self.status_widget = QWidget()
        self.status_widget.setFixedWidth(STATUS_WIDTH)
        status_layout = QVBoxLayout(self.status_widget)
        status_layout.setContentsMargins(0, 0, 0, 0)
        status_layout.setSpacing(3)
        status_layout.setAlignment(Qt.AlignCenter)

        self.status_row = QWidget()
        status_row_layout = QHBoxLayout(self.status_row)
        status_row_layout.setContentsMargins(0, 0, 0, 0)
        status_row_layout.setSpacing(4)
        status_row_layout.setAlignment(Qt.AlignCenter)

        self.status_label = QLabel()
        self.status_label.setObjectName("StatusPill")
        self.status_label.setAlignment(Qt.AlignCenter)
        self.status_label.setMinimumWidth(72)
        self.status_label.setMaximumHeight(28)
        status_row_layout.addWidget(self.status_label)

        status_layout.addWidget(self.status_row, 0, Qt.AlignCenter)

        self.progress_text = QLabel("")
        self.progress_text.setObjectName("MetaText")
        self.progress_text.setAlignment(Qt.AlignCenter)
        status_layout.addWidget(self.progress_text, 0, Qt.AlignCenter)

        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.setTextVisible(False)
        self.progress_bar.setFixedHeight(4)
        self.progress_bar.setFixedWidth(76)
        status_layout.addWidget(self.progress_bar, 0, Qt.AlignCenter)
        outer.addWidget(self.status_widget)

        self.actions_widget = QWidget()
        self.actions_widget.setFixedWidth(ACTIONS_WIDTH)
        actions = QHBoxLayout(self.actions_widget)
        actions.setContentsMargins(0, 0, 0, 0)
        actions.setSpacing(4)
        actions.setAlignment(Qt.AlignCenter)
        self.open_folder_button = ActionIconButton("folder")
        self.open_folder_button.setToolTip("폴더 열기")
        self.open_folder_button.clicked.connect(self._open_folder)
        actions.addWidget(self.open_folder_button)

        self.remove_button = ActionIconButton("remove")
        self.remove_button.setToolTip("목록에서 삭제")
        self.remove_button.clicked.connect(self._remove_row)
        actions.addWidget(self.remove_button)

        self.delete_file_button = ActionIconButton("trash")
        self.delete_file_button.setToolTip("파일 삭제")
        self.delete_file_button.clicked.connect(self._delete_file)
        actions.addWidget(self.delete_file_button)

        self.more_button = ActionIconButton("more")
        self.more_button.setToolTip("더보기")
        actions.addWidget(self.more_button)
        outer.addWidget(self.actions_widget)

    def mousePressEvent(self, event):
        self.owner.select_row_for_widget(self)
        super().mousePressEvent(event)

    def enterEvent(self, event):
        self.setProperty("hovered", "true")
        self._repolish()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self.setProperty("hovered", "false")
        self._repolish()
        super().leaveEvent(event)

    def refresh(self):
        candidate = self.owner.selected_candidate_for_row_ref(self.row) or self.row["candidate"]
        title = candidate.get("display_title") or candidate.get("title") or "media"
        self.title_label.setText(str(title))
        self.title_label.setToolTip(str(title))
        self.info_label.setText(row_info_text(candidate))
        self.size_label.setText(engine.display_size(candidate.get("sort_bytes")))
        self._refresh_quality_combo()
        self._refresh_format_combo()
        self._refresh_source_button()
        self.set_status(self.row.get("status") or "준비", self.row.get("status_detail") or "")
        self.set_progress(self.row.get("progress") or 0, self.row.get("progress_text") or "")
        self._refresh_actions()

    def _refresh_quality_combo(self):
        options = self.row.get("quality_options") or []
        current = max(0, min(int(self.row.get("selected_index") or 0), len(options) - 1)) if options else 0
        self.row["selected_index"] = current
        self.quality_combo.blockSignals(True)
        self.quality_combo.clear()
        for option in options:
            self.quality_combo.addItem(option["label"])
        self.quality_combo.setCurrentIndex(current)
        self.quality_combo.blockSignals(False)
        self.quality_value_label.setText(self.quality_combo.currentText())
        self._refresh_quality_mode()

    def _refresh_format_combo(self):
        option = self.owner.selected_quality_option_for_row_ref(self.row)
        formats = option.get("formats") if option else []
        current = max(0, min(int(self.row.get("selected_format_index") or 0), len(formats) - 1)) if formats else 0
        self.row["selected_format_index"] = current
        self.format_combo.blockSignals(True)
        self.format_combo.clear()
        for item in formats:
            self.format_combo.addItem(item["label"])
        self.format_combo.setCurrentIndex(current)
        self.format_combo.blockSignals(False)
        candidate = self.owner.selected_candidate_for_row_ref(self.row) or self.row["candidate"]
        label = format_display_label(candidate)
        self.format_label.setText(label)

    def _refresh_source_button(self):
        source_url = self.row.get("source_url") or ""
        domain = source_domain(source_url)
        self.domain_label.setText(domain)
        self.site_button.setToolTip(f"{domain}\n원본 링크 열기")
        self.site_button.setEnabled(bool(source_url))

    def _refresh_actions(self):
        active = self.row.get("status") in {"분석 중", "다운로드 중"}
        output_path = Path(self.row.get("output_path") or "")
        self.remove_button.setEnabled(not active)
        self.delete_file_button.setEnabled(bool(self.row.get("output_path")) and output_path.exists() and not active)

    def _refresh_quality_mode(self):
        status = self.row.get("status") or "준비"
        completed = status == "완료"
        self.quality_value_label.setText(self.quality_combo.currentText())
        self.quality_combo.setHidden(completed)
        self.quality_value_label.setHidden(not completed)
        self.format_combo.setHidden(completed)
        self.format_label.setHidden(not completed)
        locked_value = "true" if completed else "false"
        self.quality_value_label.setProperty("locked", locked_value)
        self.format_label.setProperty("locked", locked_value)
        for widget in (self.quality_value_label, self.format_label):
            widget.style().unpolish(widget)
            widget.style().polish(widget)
            widget.update()

    def _quality_changed(self, index):
        self.owner.quality_changed_for_row(self.row, index)

    def _format_changed(self, index):
        self.owner.format_changed_for_row(self.row, index)

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
        self.status_label.setText(status)
        self.status_label.setToolTip(detail or status)
        self.status_label.setStyleSheet(STATUS_STYLES.get(status, STATUS_STYLES["준비"]))
        self._refresh_quality_mode()
        self._refresh_actions()

    def set_progress(self, value, text=""):
        bounded = max(0, min(100, int(float(value or 0))))
        self.row["progress"] = bounded
        active = self.row.get("status") in {"분석 중", "다운로드 중"}
        error_detail = self.row.get("status") == "오류" and self.row.get("status_detail")
        display_text = text if active else (self.row.get("status_detail") if error_detail else "")
        self.row["progress_text"] = display_text
        self.progress_bar.setValue(bounded)
        show_progress = active and (bool(display_text) or bounded > 0)
        self.progress_bar.setVisible(show_progress)
        self.progress_text.setVisible(bool(display_text))
        self.progress_text.setText(display_text)
