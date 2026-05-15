# Auto-split from clipflow_qt.py; keep behavior changes in focused commits.
from PySide6.QtCore import QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPen, QPolygonF
from PySide6.QtWidgets import QComboBox, QFrame, QLineEdit, QToolButton, QWidget

try:
    from tools.clipflow_theme import THUMBNAIL_WIDTH
except ImportError:
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


class LineIcon(QWidget):
    def __init__(self, icon_kind, parent=None):
        super().__init__(parent)
        self.icon_kind = icon_kind
        self.setFixedSize(22, 22)

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        color = QColor("#52627a")
        pen = QPen(color, 1.7)
        pen.setCapStyle(Qt.RoundCap)
        pen.setJoinStyle(Qt.RoundJoin)
        painter.setPen(pen)
        painter.setBrush(Qt.NoBrush)

        if self.icon_kind == "link":
            painter.drawArc(QRectF(4, 8, 9, 9), 35 * 16, 240 * 16)
            painter.drawArc(QRectF(9, 5, 9, 9), 215 * 16, 240 * 16)
            painter.drawLine(QPointF(9, 12), QPointF(13, 8))
        elif self.icon_kind == "folder":
            painter.drawLine(QPointF(4, 8), QPointF(9, 8))
            painter.drawLine(QPointF(9, 8), QPointF(11, 10))
            painter.drawLine(QPointF(11, 10), QPointF(18, 10))
            painter.drawRoundedRect(QRectF(3, 10, 16, 9), 2, 2)
        elif self.icon_kind == "cookie":
            painter.drawEllipse(QPointF(11, 11), 7, 7)
            painter.setBrush(color)
            painter.drawEllipse(QPointF(8, 9), 1.1, 1.1)
            painter.drawEllipse(QPointF(12, 13), 1.0, 1.0)
            painter.drawEllipse(QPointF(14, 8), 0.9, 0.9)
        elif self.icon_kind == "clock":
            painter.drawEllipse(QPointF(11, 11), 7, 7)
            painter.drawLine(QPointF(11, 11), QPointF(11, 7))
            painter.drawLine(QPointF(11, 11), QPointF(14, 13))
        elif self.icon_kind == "file":
            painter.drawRoundedRect(QRectF(6, 4, 11, 15), 1.5, 1.5)
            painter.drawLine(QPointF(13, 4), QPointF(17, 8))
            painter.drawLine(QPointF(13, 4), QPointF(13, 8))
            painter.drawLine(QPointF(13, 8), QPointF(17, 8))
            painter.drawLine(QPointF(8, 12), QPointF(15, 12))
            painter.drawLine(QPointF(8, 15), QPointF(14, 15))


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
        border_color = "#a9c5ef" if enabled and hovered else "#d7e0ec"
        border = QColor(border_color if enabled else "#e1e8f3")
        text = QColor("#111827" if enabled else "#98a2b3")
        background = QColor("#fbfdff" if enabled and hovered else ("#ffffff" if enabled else "#f8fafc"))

        painter.setPen(QPen(border, 1))
        painter.setBrush(background)
        painter.drawRoundedRect(rect, 6, 6)

        text_left = 38 if self.icon_kind else 11
        if self.icon_kind == "cookie":
            painter.save()
            painter.setPen(QPen(QColor("#52627a"), 1.5))
            painter.setBrush(Qt.NoBrush)
            center = QPointF(22, self.height() / 2)
            painter.drawEllipse(center, 7, 7)
            painter.setBrush(QColor("#52627a"))
            painter.setPen(Qt.NoPen)
            painter.drawEllipse(QPointF(center.x() - 3, center.y() - 2), 1.1, 1.1)
            painter.drawEllipse(QPointF(center.x() + 2, center.y() + 2), 1.0, 1.0)
            painter.drawEllipse(QPointF(center.x() + 3, center.y() - 4), 0.9, 0.9)
            painter.restore()

        text_rect = self.rect().adjusted(text_left, 0, -28, 0)
        painter.setPen(text)
        painter.drawText(text_rect, Qt.AlignVCenter | Qt.AlignLeft, self.currentText())

        arrow_pen = QPen(QColor("#344054" if enabled else "#aeb8c7"), 1.6)
        arrow_pen.setCapStyle(Qt.RoundCap)
        painter.setPen(arrow_pen)
        center_x = self.width() - 17
        center_y = self.height() // 2
        painter.drawLine(QPointF(center_x - 4, center_y - 1), QPointF(center_x, center_y + 3))
        painter.drawLine(QPointF(center_x, center_y + 3), QPointF(center_x + 4, center_y - 1))

    def enterEvent(self, event):
        self.update()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self.update()
        super().leaveEvent(event)


class ActionIconButton(QToolButton):
    def __init__(self, icon_kind, parent=None):
        super().__init__(parent)
        self.icon_kind = icon_kind
        self.setObjectName("ActionButton")
        self.setFixedSize(26, 26)
        self.setCursor(Qt.PointingHandCursor)

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        hovered = self.underMouse() and self.isEnabled()
        if hovered:
            painter.setPen(Qt.NoPen)
            painter.setBrush(QColor("#e6f0ff" if not self.isDown() else "#d7e7ff"))
            painter.drawRoundedRect(QRectF(self.rect()).adjusted(2, 2, -2, -2), 6, 6)

        if not self.isEnabled():
            color = QColor("#aeb8c7")
        elif self.isDown():
            color = QColor("#1e40af")
        elif hovered:
            color = QColor("#1d4ed8")
        else:
            color = QColor("#243b5a")
        pen = QPen(color, 1.8)
        pen.setCapStyle(Qt.RoundCap)
        pen.setJoinStyle(Qt.RoundJoin)
        painter.setPen(pen)
        painter.setBrush(Qt.NoBrush)

        if self.icon_kind == "folder":
            painter.drawLine(QPointF(7, 10), QPointF(12, 10))
            painter.drawLine(QPointF(12, 10), QPointF(14, 12))
            painter.drawLine(QPointF(14, 12), QPointF(21, 12))
            painter.drawRoundedRect(QRectF(6, 12, 16, 10), 2, 2)
        elif self.icon_kind == "remove":
            painter.drawLine(QPointF(9, 9), QPointF(19, 19))
            painter.drawLine(QPointF(19, 9), QPointF(9, 19))
        elif self.icon_kind == "trash":
            painter.drawLine(QPointF(10, 9), QPointF(18, 9))
            painter.drawLine(QPointF(12, 7), QPointF(16, 7))
            painter.drawRoundedRect(QRectF(10, 11, 8, 11), 1.5, 1.5)
            painter.drawLine(QPointF(13, 13), QPointF(13, 20))
            painter.drawLine(QPointF(15, 13), QPointF(15, 20))
        elif self.icon_kind == "more":
            painter.setBrush(color)
            painter.setPen(Qt.NoPen)
            for y in (9, 14, 19):
                painter.drawEllipse(QPointF(14, y), 1.35, 1.35)

    def enterEvent(self, event):
        self.update()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self.update()
        super().leaveEvent(event)


class ThumbnailBox(QFrame):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ThumbBox")
        self.setFixedSize(THUMBNAIL_WIDTH, 54)

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        gradient = QLinearGradient(rect.topLeft(), rect.bottomRight())
        gradient.setColorAt(0.0, QColor("#eef4fb"))
        gradient.setColorAt(0.55, QColor("#e1eaf4"))
        gradient.setColorAt(1.0, QColor("#d8e2ef"))

        painter.setPen(QPen(QColor("#d6e1ef"), 1))
        painter.setBrush(gradient)
        painter.drawRoundedRect(rect, 6, 6)

        painter.setPen(QPen(QColor("#cfdae8"), 1))
        for y in (15, 31, 47):
            painter.drawLine(QPointF(8, y), QPointF(88, y - 13))

        painter.setBrush(QColor("#8fa2ba"))
        painter.setPen(Qt.NoPen)
        center = QPointF(self.width() / 2 + 2, self.height() / 2)
        triangle = QPolygonF([
            QPointF(center.x() - 6, center.y() - 8),
            QPointF(center.x() - 6, center.y() + 8),
            QPointF(center.x() + 8, center.y()),
        ])
        painter.drawPolygon(triangle)
