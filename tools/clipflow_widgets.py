from PySide6.QtCore import QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QComboBox, QFrame, QLineEdit

try:
    from tools.clipflow_icons import ICON_COLOR, ICON_DISABLED_COLOR, ICON_HOVER_COLOR, LucideIconWidget, lucide_pixmap
    from tools.clipflow_theme import THUMBNAIL_WIDTH
except ImportError:
    from clipflow_icons import ICON_COLOR, ICON_DISABLED_COLOR, ICON_HOVER_COLOR, LucideIconWidget, lucide_pixmap
    from clipflow_theme import THUMBNAIL_WIDTH


class ClearingUrlInput(QLineEdit):
    clicked_for_edit = Signal()

    def mousePressEvent(self, event):
        self.clicked_for_edit.emit()
        super().mousePressEvent(event)


class PathDisplayInput(QLineEdit):
    def __init__(self, text="", parent=None):
        super().__init__(text, parent)
        self.setReadOnly(True)
        self.setFocusPolicy(Qt.NoFocus)
        self.setCursor(Qt.ArrowCursor)

    def mousePressEvent(self, event):
        self.deselect()
        self.clearFocus()
        event.accept()

    def mouseMoveEvent(self, event):
        event.accept()

    def mouseDoubleClickEvent(self, event):
        self.deselect()
        event.accept()

    def keyPressEvent(self, event):
        event.ignore()


class CleanComboBox(QComboBox):
    def __init__(self, icon_kind=None, parent=None):
        super().__init__(parent)
        self.icon_kind = icon_kind
        self.setMinimumHeight(28)
        self.setMaximumHeight(30)

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        rect = QRectF(self.rect()).adjusted(0.5, 1.0, -0.5, -1.0)
        enabled = self.isEnabled()
        hovered = self.underMouse()
        border_color = "#BFD0E6" if enabled and hovered else "#DCE6F2"
        text_color = "#111827" if enabled else "#98A2B3"
        background = "#FBFDFF" if enabled and hovered else ("#FFFFFF" if enabled else "#F8FAFC")

        painter.setPen(QPen(QColor(border_color if enabled else "#E2E8F0"), 1))
        painter.setBrush(QColor(background))
        painter.drawRoundedRect(rect, 6, 6)

        text_left = 38 if self.icon_kind else 11
        if self.icon_kind:
            icon_color = ICON_HOVER_COLOR if enabled and hovered else (ICON_COLOR if enabled else ICON_DISABLED_COLOR)
            painter.drawPixmap(14, (self.height() - 16) // 2, lucide_pixmap(self.icon_kind, 16, icon_color))

        text_rect = self.rect().adjusted(text_left, 0, -28, 0)
        painter.setPen(QColor(text_color))
        painter.drawText(text_rect, Qt.AlignVCenter | Qt.AlignLeft, self.currentText())

        arrow_color = ICON_HOVER_COLOR if enabled and hovered else (ICON_COLOR if enabled else ICON_DISABLED_COLOR)
        painter.drawPixmap(self.width() - 22, (self.height() - 14) // 2, lucide_pixmap("chevron-down", 14, arrow_color))

    def enterEvent(self, event):
        self.update()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self.update()
        super().leaveEvent(event)

    def changeEvent(self, event):
        self.update()
        super().changeEvent(event)


class ThumbnailPlaceholder(QFrame):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ThumbBox")
        self.setFixedSize(THUMBNAIL_WIDTH, 54)
        self.icon = LucideIconWidget("video", size=24, color="#94A3B8", parent=self)

    def resizeEvent(self, event):
        self.icon.move((self.width() - self.icon.width()) // 2, (self.height() - self.icon.height()) // 2)
        super().resizeEvent(event)
