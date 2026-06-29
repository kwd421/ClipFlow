from pathlib import Path
from functools import lru_cache

from PySide6.QtCore import QEvent, QObject, QPoint, QRectF, Qt
from PySide6.QtGui import QColor, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import QLabel, QToolButton, QWidget

try:
    from tools import clipflow_theme as theme
except ImportError:
    import clipflow_theme as theme


LUCIDE_ICON_DIR = Path(__file__).resolve().parents[1] / "assets" / "icons" / "lucide"
ICON_COLOR = theme.ICON
ICON_HOVER_COLOR = theme.ICON_HOVER
ICON_ACTIVE_COLOR = theme.ICON_ACTIVE
ICON_DISABLED_COLOR = theme.ICON_DISABLED
ICON_DANGER_COLOR = theme.DANGER
ICON_DANGER_HOVER_COLOR = theme.DANGER_HOVER
ICON_DANGER_ACTIVE_COLOR = theme.DANGER_PRESSED


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

    def tooltip_position(self):
        return self.mapToGlobal(QPoint(0, -self.sizeHint().height() - 10))

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
                painter.setBrush(QColor(theme.DANGER_TINT_STRONG if self.isDown() else theme.DANGER_TINT))
            else:
                painter.setBrush(QColor(theme.ACCENT_TINT_STRONG if self.isDown() else theme.ACCENT_TINT))
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


class CustomTooltip(QLabel):
    """A single, app-wide tooltip popup.

    It is transparent to mouse events and shown above the hovered widget, which
    avoids the native-tooltip flicker (the cursor never lands on the tooltip)
    and the platform dark-mode background leaking through.
    """

    _instance = None

    def __init__(self):
        super().__init__(None, Qt.ToolTip | Qt.FramelessWindowHint)
        self.setAttribute(Qt.WA_TransparentForMouseEvents, True)
        self.setAttribute(Qt.WA_ShowWithoutActivating, True)
        self.setObjectName("CustomTooltip")
        self.setWordWrap(False)
        self.setStyleSheet(
            "QLabel#CustomTooltip {"
            f" background: {theme.INK}; color: {theme.SURFACE};"
            " border-radius: 7px; padding: 7px 10px; font-size: 12px;"
            " }"
        )

    @classmethod
    def instance(cls):
        if cls._instance is None:
            cls._instance = CustomTooltip()
        return cls._instance


def show_tooltip_above(widget, text):
    text = str(text or "").strip()
    if not text:
        hide_tooltip()
        return
    tip = CustomTooltip.instance()
    tip.setText(text)
    tip.adjustSize()
    origin = widget.mapToGlobal(QPoint(0, 0))
    x = origin.x() + (widget.width() - tip.width()) // 2
    y = origin.y() - tip.height() - 8
    tip.move(x, y)
    tip.show()
    tip.raise_()


def hide_tooltip():
    if CustomTooltip._instance is not None:
        CustomTooltip._instance.hide()


class TooltipManager(QObject):
    """App-wide event filter that replaces native tooltips with CustomTooltip."""

    def eventFilter(self, obj, event):
        event_type = event.type()
        if event_type == QEvent.ToolTip:
            if isinstance(obj, QWidget) and obj.toolTip():
                show_tooltip_above(obj, obj.toolTip())
                return True
            hide_tooltip()
            return False
        if event_type in (QEvent.Leave, QEvent.HoverLeave, QEvent.WindowDeactivate, QEvent.MouseButtonPress):
            hide_tooltip()
        return False
