from pathlib import Path
from functools import lru_cache

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import QToolButton, QWidget


LUCIDE_ICON_DIR = Path(__file__).resolve().parents[1] / "assets" / "icons" / "lucide"
ICON_COLOR = "#64748B"
ICON_HOVER_COLOR = "#2563EB"
ICON_ACTIVE_COLOR = "#1D4ED8"
ICON_DISABLED_COLOR = "#CBD5E1"
ICON_DANGER_COLOR = "#EF4444"
ICON_DANGER_HOVER_COLOR = "#DC2626"
ICON_DANGER_ACTIVE_COLOR = "#B91C1C"


def icon_path(name):
    return LUCIDE_ICON_DIR / f"{name}.svg"


def lucide_svg(name, color=ICON_COLOR):
    path = icon_path(name)
    data = path.read_text(encoding="utf-8")
    return data.replace("currentColor", color)


@lru_cache(maxsize=256)
def lucide_pixmap(name, size=20, color=ICON_COLOR, scale=2):
    renderer = QSvgRenderer(lucide_svg(name, color).encode("utf-8"))
    pixel_size = int(size * scale)
    pixmap = QPixmap(pixel_size, pixel_size)
    pixmap.fill(Qt.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)
    renderer.render(painter, QRectF(0, 0, pixel_size, pixel_size))
    painter.end()
    pixmap.setDevicePixelRatio(scale)
    return pixmap


class LucideIconWidget(QWidget):
    def __init__(self, icon_name, size=22, color=ICON_COLOR, parent=None):
        super().__init__(parent)
        self.icon_name = icon_name
        self.icon_size = size
        self.color = color
        self.setFixedSize(size, size)

    def set_color(self, color):
        self.color = color
        self.update()

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.drawPixmap(0, 0, self.icon_size, self.icon_size, lucide_pixmap(self.icon_name, self.icon_size, self.color))


class LucideIconButton(QToolButton):
    def __init__(
        self,
        icon_name,
        size=26,
        icon_size=18,
        parent=None,
        danger=False,
        icon_color=None,
        background=None,
        hover_background=None,
    ):
        super().__init__(parent)
        self.icon_name = icon_name
        self.icon_size = icon_size
        self.danger = danger
        self.icon_color = icon_color
        self.background = background
        self.hover_background = hover_background
        self.setObjectName("ActionButton")
        self.setProperty("danger", "true" if danger else "false")
        self.setFixedSize(size, size)
        self.setCursor(Qt.PointingHandCursor)

    def _icon_color(self):
        if not self.isEnabled():
            return ICON_DISABLED_COLOR
        if self.danger and self.isDown():
            return ICON_DANGER_ACTIVE_COLOR
        if self.danger and self.underMouse():
            return ICON_DANGER_HOVER_COLOR
        if self.danger:
            return ICON_DANGER_COLOR
        if self.icon_color:
            return self.icon_color
        if self.isDown():
            return ICON_ACTIVE_COLOR
        if self.underMouse():
            return ICON_HOVER_COLOR
        return ICON_COLOR

    def paintEvent(self, event):
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        if self.background and self.isEnabled():
            painter.setPen(Qt.NoPen)
            background = self.hover_background if self.underMouse() and self.hover_background else self.background
            painter.setBrush(QColor(background))
            painter.drawRoundedRect(QRectF(self.rect()).adjusted(1, 1, -1, -1), 5, 5)
        elif self.underMouse() and self.isEnabled():
            painter.setPen(Qt.NoPen)
            if self.danger:
                painter.setBrush(QColor("#FEE2E2" if not self.isDown() else "#FECACA"))
            else:
                painter.setBrush(QColor("#EAF2FF" if not self.isDown() else "#DBEAFE"))
            painter.drawRoundedRect(QRectF(self.rect()).adjusted(2, 2, -2, -2), 6, 6)

        pixmap = lucide_pixmap(self.icon_name, self.icon_size, self._icon_color())
        x = (self.width() - self.icon_size) // 2
        y = (self.height() - self.icon_size) // 2
        painter.drawPixmap(x, y, self.icon_size, self.icon_size, pixmap)

    def enterEvent(self, event):
        self.update()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self.update()
        super().leaveEvent(event)

    def changeEvent(self, event):
        self.update()
        super().changeEvent(event)

    def mousePressEvent(self, event):
        self.update()
        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event):
        super().mouseReleaseEvent(event)
        self.update()
