from PySide6.QtCore import Qt
from PySide6.QtWidgets import QDialog, QGridLayout, QHBoxLayout, QLabel, QVBoxLayout

try:
    from tools import candidate_presenter as presenter
    from tools.clipflow_widgets import CleanComboBox, CleanSwitch, OutlinedButton
except ImportError:
    import candidate_presenter as presenter
    from clipflow_widgets import CleanComboBox, CleanSwitch, OutlinedButton


def _combo_text(combo):
    return str(combo.currentText()).strip()


PREFERENCE_TOOLTIPS = {
    "화질": "자동이면 가능한 가장 높은 해상도를 고릅니다. 숫자를 고르면 그 해상도 이하에서 가장 좋은 후보를 고릅니다.",
    "포맷": "저장할 파일 형식입니다. MP3/WAV/AAC는 음원만 저장합니다.",
    "코덱": "자동이면 코덱을 제한하지 않고 best 방식으로 고릅니다. 특정 코덱을 고르면 그 코덱을 우선합니다.",
    "HDR": "끔이면 SDR 후보를 우선합니다. 켬이면 HDR 후보도 허용합니다.",
}


class PreferencesDialog(QDialog):
    def __init__(self, preferences, parent=None):
        super().__init__(parent)
        self.setWindowTitle("품질 설정")
        self.setModal(True)
        self.setMinimumWidth(360)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 14)
        layout.setSpacing(12)

        form = QGridLayout()
        form.setContentsMargins(0, 0, 0, 0)
        form.setHorizontalSpacing(12)
        form.setVerticalSpacing(10)

        self.quality_combo = CleanComboBox()
        self.quality_combo.addItems(["자동", "4320p", "2160p", "1440p", "1080p", "720p", "480p", "360p"])
        self.format_combo = CleanComboBox()
        self.format_combo.addItems(["자동", "MP4", "WEBM", "MP3", "WAV", "AAC"])
        self.codec_combo = CleanComboBox()
        self.codec_combo.addItems(["자동", "H264", "H265", "AV1", "VP9"])
        self.hdr_switch = CleanSwitch()
        self.hdr_switch.setChecked(str(preferences.hdr).strip() == "켬")

        self.quality_combo.setCurrentText(preferences.quality)
        self.format_combo.setCurrentText(preferences.output_format)
        self.codec_combo.setCurrentText(preferences.codec)
        self.format_combo.currentIndexChanged.connect(self.refresh_controls)

        for row, (label, combo) in enumerate(
            (
                ("화질", self.quality_combo),
                ("포맷", self.format_combo),
                ("코덱", self.codec_combo),
            )
        ):
            label_widget = QLabel(label)
            label_widget.setObjectName("MetaText")
            label_widget.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            tooltip = PREFERENCE_TOOLTIPS.get(label, "")
            if tooltip:
                label_widget.setToolTip(tooltip)
                combo.setToolTip(tooltip)
            form.addWidget(label_widget, row, 0)
            combo.show_arrow = False
            combo.text_alignment = Qt.AlignCenter
            combo.setFixedWidth(120)
            form.addWidget(combo, row, 1)
        hdr_label = QLabel("HDR")
        hdr_label.setObjectName("MetaText")
        hdr_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        hdr_tooltip = PREFERENCE_TOOLTIPS["HDR"]
        hdr_label.setToolTip(hdr_tooltip)
        self.hdr_switch.setToolTip(hdr_tooltip)
        form.addWidget(hdr_label, 3, 0)
        form.addWidget(self.hdr_switch, 3, 1, Qt.AlignCenter)

        layout.addLayout(form)
        buttons = QHBoxLayout()
        buttons.addStretch(1)
        self.cancel_button = OutlinedButton("취소")
        self.cancel_button.setObjectName("SecondaryButton")
        self.ok_button = OutlinedButton("확인")
        self.ok_button.setObjectName("PrimaryPopupButton")
        self.ok_button.setDefault(True)
        self.ok_button.setAutoDefault(True)
        self.cancel_button.clicked.connect(self.reject)
        self.ok_button.clicked.connect(self.accept)
        buttons.addWidget(self.cancel_button)
        buttons.addWidget(self.ok_button)
        layout.addLayout(buttons)
        self.refresh_controls()

    def refresh_controls(self):
        audio_format = self.format_combo.currentText().strip().lower() in presenter.AUDIO_FORMATS
        self.quality_combo.setEnabled(not audio_format)
        self.codec_combo.setEnabled(not audio_format)
        self.hdr_switch.setEnabled(not audio_format)

    def preferences(self):
        return presenter.DownloadPreferences(
            quality=_combo_text(self.quality_combo),
            output_format=_combo_text(self.format_combo),
            codec=_combo_text(self.codec_combo),
            frame_rate="자동",
            hdr="켬" if self.hdr_switch.isChecked() else "끔",
        )


class DeleteConfirmDialog(QDialog):
    def __init__(self, output_path, parent=None, title_text=None, detail_text=None, window_title=None):
        super().__init__(parent)
        self.setWindowTitle(window_title or "파일 삭제")
        self.setModal(True)
        self.setMinimumWidth(420)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 14)
        layout.setSpacing(12)

        title = QLabel(title_text or "파일을 삭제하시겠습니까?")
        title.setObjectName("SectionTitle")
        detail = QLabel(detail_text if detail_text is not None else str(output_path))
        detail.setObjectName("MetaText")
        detail.setWordWrap(True)
        layout.addWidget(title)
        layout.addWidget(detail)

        buttons = QHBoxLayout()
        buttons.addStretch(1)
        self.cancel_button = OutlinedButton("No")
        self.cancel_button.setObjectName("SecondaryButton")
        self.cancel_button.setFixedSize(64, 34)
        self.ok_button = OutlinedButton("Yes")
        self.ok_button.setObjectName("PrimaryPopupButton")
        self.ok_button.setFixedSize(64, 34)
        self.ok_button.setDefault(True)
        self.ok_button.setAutoDefault(True)
        self.cancel_button.clicked.connect(self.reject)
        self.ok_button.clicked.connect(self.accept)
        buttons.addWidget(self.cancel_button)
        buttons.addWidget(self.ok_button)
        layout.addLayout(buttons)
