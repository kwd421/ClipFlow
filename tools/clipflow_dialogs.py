from PySide6.QtCore import Qt
from PySide6.QtWidgets import QGridLayout, QLabel

try:
    from tools import candidate_presenter as presenter
    from tools.clipflow_widgets import AppDialog, CleanCheckBox, CleanComboBox, CleanSwitch, OutlinedButton
except ImportError:
    import candidate_presenter as presenter
    from clipflow_widgets import AppDialog, CleanCheckBox, CleanComboBox, CleanSwitch, OutlinedButton


def _combo_text(combo):
    return str(combo.currentText()).strip()


PREFERENCE_TOOLTIPS = {
    "화질": "자동이면 가능한 가장 높은 해상도를 고릅니다. 숫자를 고르면 그 해상도 이하에서 가장 좋은 후보를 고릅니다.",
    "포맷": "저장할 파일 형식입니다. MP3/WAV/AAC는 음원만 저장합니다.",
    "코덱": "자동이면 코덱을 제한하지 않고 best 방식으로 고릅니다. 특정 코덱을 고르면 그 코덱을 우선합니다.",
    "HDR": "끔이면 SDR 후보를 우선합니다. 켬이면 HDR 후보도 허용합니다.",
}


class PreferencesDialog(AppDialog):
    def __init__(self, preferences, parent=None):
        super().__init__(
            parent,
            window_title="품질 설정",
            title_text="",
            detail_text="",
            minimum_width=360,
        )
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

        from PySide6.QtWidgets import QWidget

        form_host = QWidget(self)
        form_host.setLayout(form)
        self.add_body(form_host)
        self.add_action("cancel", "취소", "SecondaryButton", reject=True, min_width=72)
        self.ok_button = self.add_action("ok", "확인", "PrimaryPopupButton", default=True, choice="ok", min_width=72)
        self.cancel_button = self.button("cancel")
        self.finalize()
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


class DeleteConfirmDialog(AppDialog):
    """File delete / playlist remove confirmations on the shared AppDialog shell."""

    def __init__(
        self,
        output_path,
        parent=None,
        title_text=None,
        detail_text=None,
        window_title=None,
        permanent_delete=False,
        on_permanent_delete_changed=None,
        cancel_text="No",
        ok_text="Yes",
        ok_style="PrimaryPopupButton",
        alt_button_text=None,
        alt_style="PrimaryPopupButton",
        show_permanent_delete=True,
        compact_action_buttons=True,
    ):
        super().__init__(
            parent,
            window_title=window_title or "파일 삭제",
            title_text=title_text or "파일을 삭제하시겠습니까?",
            detail_text=detail_text if detail_text is not None else str(output_path),
            minimum_width=420,
        )
        self._on_permanent_delete_changed = on_permanent_delete_changed

        self.permanent_delete_check = CleanCheckBox("영구 삭제")
        self.permanent_delete_check.setToolTip("사용 시 휴지통을 거치지 않고 즉시 삭제합니다. 설정은 저장됩니다.")
        self.permanent_delete_check.setChecked(bool(permanent_delete))
        self.permanent_delete_check.toggled.connect(self._permanent_delete_toggled)
        if show_permanent_delete:
            self.add_footer_leading(self.permanent_delete_check)
        else:
            self.permanent_delete_check.hide()

        compact = bool(compact_action_buttons and not alt_button_text)
        self.cancel_button = self.add_action(
            "cancel",
            cancel_text or "No",
            "SecondaryButton",
            reject=True,
            fixed_size=(64, 34) if compact else None,
            min_width=None if compact else 72,
        )
        self.alt_button = None
        if alt_button_text:
            self.alt_button = self.add_action(
                "alt",
                alt_button_text,
                alt_style or "PrimaryPopupButton",
                choice="alt",
                min_width=110,
            )
        self.ok_button = self.add_action(
            "ok",
            ok_text or "Yes",
            ok_style or "PrimaryPopupButton",
            default=True,
            choice="ok",
            fixed_size=(64, 34) if compact else None,
            min_width=None if compact else 96,
        )
        self.finalize()

    def permanent_delete_enabled(self):
        return bool(self.permanent_delete_check.isChecked())

    def _permanent_delete_toggled(self, checked):
        callback = self._on_permanent_delete_changed
        if callback is not None:
            callback(bool(checked))

    @classmethod
    def for_playlist_remove(
        cls,
        completed_count=0,
        incomplete_count=0,
        analyzing=False,
        parent=None,
    ):
        detail_parts = [f"완료 {int(completed_count or 0)}개"]
        if incomplete_count:
            detail_parts.append(f"미완료 {int(incomplete_count or 0)}개")
        if analyzing:
            detail_parts.append("분석 진행 중")
        detail = (
            " · ".join(detail_parts)
            + "\n목록에서만 제거합니다. 저장된 파일은 삭제되지 않습니다."
            + "\n완료 항목만 남기거나 재생목록 카드 전체를 목록에서 제거할 수 있습니다."
        )
        return cls(
            "",
            parent=parent,
            title_text="재생목록을 목록에서 제거하시겠습니까?",
            detail_text=detail,
            window_title="목록에서 삭제",
            cancel_text="취소",
            ok_text="전체 제거",
            ok_style="DangerButton",
            alt_button_text="완료만 남기기",
            alt_style="PrimaryPopupButton",
            show_permanent_delete=False,
            compact_action_buttons=False,
        )

    @classmethod
    def for_bulk_selection(cls, count, delete_files=True, permanent_delete=False, on_permanent_delete_changed=None, parent=None):
        if delete_files:
            title = f"선택한 {count}개 항목의 파일을 삭제하시겠습니까?"
            detail = "다운로드된 파일이 삭제되며 목록에서도 제거됩니다."
            ok_style = "DangerButton"
            ok_text = "삭제"
            show_perm = True
        else:
            title = f"선택한 {count}개 항목을 목록에서 삭제하시겠습니까?"
            detail = "파일은 유지되고 목록에서만 제거됩니다."
            ok_style = "PrimaryPopupButton"
            ok_text = "삭제"
            show_perm = False
        return cls(
            "",
            parent=parent,
            title_text=title,
            detail_text=detail,
            window_title="파일 삭제" if delete_files else "목록에서 삭제",
            cancel_text="취소",
            ok_text=ok_text,
            ok_style=ok_style,
            permanent_delete=permanent_delete,
            on_permanent_delete_changed=on_permanent_delete_changed,
            show_permanent_delete=show_perm,
            compact_action_buttons=False,
        )
