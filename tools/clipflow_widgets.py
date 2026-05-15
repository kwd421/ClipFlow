import html as html_lib
import re
from urllib.parse import urljoin, urlparse

from PySide6.QtCore import QPoint, QRectF, QSize, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QIcon, QPainter, QPen, QPixmap
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWidgets import QComboBox, QFrame, QLabel, QLineEdit, QPushButton, QToolButton, QToolTip

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


def source_domain(url):
    parsed = urlparse(str(url or ""))
    host = parsed.netloc.lower()
    if host.startswith("www."):
        host = host[4:]
    return host


LINK_TAG_RE = re.compile(r"<link\b[^>]*>", re.IGNORECASE)
ATTR_RE = re.compile(r"""([:\w-]+)\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>`]+)""", re.IGNORECASE)


def _tag_attributes(tag):
    attributes = {}
    for key, raw_value in ATTR_RE.findall(str(tag or "")):
        value = raw_value.strip()
        if len(value) >= 2 and value[0] in {"'", '"'} and value[-1] == value[0]:
            value = value[1:-1]
        attributes[key.lower()] = html_lib.unescape(value)
    return attributes


def favicon_urls_from_html(html_text, page_url):
    urls = []
    seen = set()
    for tag in LINK_TAG_RE.findall(str(html_text or "")):
        attrs = _tag_attributes(tag)
        rel = attrs.get("rel", "").lower()
        href = attrs.get("href", "").strip()
        if "icon" not in rel or not href:
            continue
        icon_url = urljoin(page_url, href)
        if icon_url and icon_url not in seen:
            seen.add(icon_url)
            urls.append(icon_url)
    return urls


def default_favicon_urls(url):
    parsed = urlparse(str(url or ""))
    scheme = parsed.scheme if parsed.scheme in {"http", "https"} else "https"
    if not parsed.netloc:
        return []
    origin = f"{scheme}://{parsed.netloc}"
    return [
        f"{origin}/favicon.ico",
        f"{origin}/favicon.png",
        f"{origin}/apple-touch-icon.png",
    ]


class AboveTooltipMixin:
    def tooltip_position(self):
        return self.mapToGlobal(QPoint(0, -self.sizeHint().height() - 10))

    def event(self, event):
        if event.type() == event.Type.ToolTip and self.toolTip():
            QToolTip.showText(self.tooltip_position(), self.toolTip(), self)
            return True
        return super().event(event)


class MarqueeLabel(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._marquee_offset = 0
        self._marquee_timer = QTimer(self)
        self._marquee_timer.setInterval(80)
        self._marquee_timer.timeout.connect(self._advance_marquee)

    def start_marquee_if_needed(self):
        overflow = self.fontMetrics().horizontalAdvance(self.text()) > max(1, self.width() - 4)
        if overflow and not self._marquee_timer.isActive():
            self._marquee_timer.start()
        elif not overflow:
            self.stop_marquee()

    def stop_marquee(self):
        self._marquee_timer.stop()
        self._marquee_offset = 0
        self.update()

    def _advance_marquee(self):
        text_width = self.fontMetrics().horizontalAdvance(self.text())
        if text_width <= self.width():
            self.stop_marquee()
            return
        self._marquee_offset = (self._marquee_offset + 2) % (text_width + 36)
        self.update()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.start_marquee_if_needed()

    def paintEvent(self, event):
        if not self._marquee_timer.isActive() or not self.text():
            super().paintEvent(event)
            return
        painter = QPainter(self)
        painter.setPen(QColor("#111827"))
        text_width = self.fontMetrics().horizontalAdvance(self.text())
        y = (self.height() + self.fontMetrics().ascent() - self.fontMetrics().descent()) // 2
        x = -self._marquee_offset
        painter.drawText(x, y, self.text())
        painter.drawText(x + text_width + 36, y, self.text())


class SourceLinkButton(AboveTooltipMixin, QToolButton):
    _network_manager = None
    _icon_cache = {}

    def __init__(self, parent=None):
        super().__init__(parent)
        self.source_url = ""
        self.favicon_url = ""
        self._reply = None
        self._icon_candidates = []
        self._seen_icon_candidates = set()
        self._page_checked = False
        self.setObjectName("SourceLinkButton")
        self.setCursor(Qt.PointingHandCursor)
        self.setToolButtonStyle(Qt.ToolButtonTextBesideIcon)
        self.setIconSize(QSize(16, 16))
        self.setAutoRaise(True)
        self._set_fallback_icon()

    @classmethod
    def network_manager(cls):
        if cls._network_manager is None:
            cls._network_manager = QNetworkAccessManager()
        return cls._network_manager

    def set_source_url(self, url):
        url = str(url or "").strip()
        if url == self.source_url:
            return
        self.source_url = url
        domain = source_domain(url)
        self.setText(domain)
        self.setToolTip(f"{domain}\n원본 링크 열기" if domain else "")
        self.setEnabled(bool(url))
        self._set_fallback_icon()
        if self._reply:
            self._reply.abort()
            self._reply = None
        if not domain:
            self.favicon_url = ""
            return
        cached = self._icon_cache.get(domain)
        if cached:
            self.setIcon(cached)
            return
        self._icon_candidates = []
        self._seen_icon_candidates = set()
        self._page_checked = False
        self._queue_icon_candidates(default_favicon_urls(url))
        self._fetch_next_icon_candidate()

    def _set_fallback_icon(self):
        self.setIcon(QIcon(lucide_pixmap("globe-2", 16, ICON_COLOR)))

    def _queue_icon_candidates(self, urls):
        for icon_url in urls:
            if not icon_url or icon_url in self._seen_icon_candidates:
                continue
            self._seen_icon_candidates.add(icon_url)
            self._icon_candidates.append(icon_url)

    def _make_request(self, url):
        request = QNetworkRequest(QUrl(url))
        request.setRawHeader(b"User-Agent", b"Mozilla/5.0")
        request.setRawHeader(b"Accept", b"text/html,image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8")
        return request

    def _fetch_next_icon_candidate(self):
        domain = source_domain(self.source_url)
        while self._icon_candidates:
            self.favicon_url = self._icon_candidates.pop(0)
            reply = self.network_manager().get(self._make_request(self.favicon_url))
            self._reply = reply
            reply.finished.connect(
                lambda reply=reply, domain=domain, favicon_url=self.favicon_url: self._favicon_finished(
                    reply, domain, favicon_url
                )
            )
            return
        if self.source_url and not self._page_checked:
            self._page_checked = True
            page_url = self.source_url
            reply = self.network_manager().get(self._make_request(page_url))
            self._reply = reply
            reply.finished.connect(lambda reply=reply, page_url=page_url: self._icon_page_finished(reply, page_url))
            return
        self.favicon_url = ""

    def _favicon_finished(self, reply, domain, favicon_url):
        if reply is not self._reply:
            reply.deleteLater()
            return
        self._reply = None
        try:
            if favicon_url != self.favicon_url:
                return
            if reply.error() != QNetworkReply.NoError:
                self._fetch_next_icon_candidate()
                return
            pixmap = QPixmap()
            if pixmap.loadFromData(reply.readAll()) and not pixmap.isNull():
                icon = QIcon(pixmap)
                self._icon_cache[domain] = icon
                if domain == source_domain(self.source_url):
                    self.setIcon(icon)
            else:
                self._fetch_next_icon_candidate()
        finally:
            reply.deleteLater()

    def _icon_page_finished(self, reply, page_url):
        if reply is not self._reply:
            reply.deleteLater()
            return
        self._reply = None
        try:
            if reply.error() == QNetworkReply.NoError:
                html_bytes = bytes(reply.readAll())
                html_text = html_bytes[:524288].decode("utf-8", "ignore")
                self._queue_icon_candidates(favicon_urls_from_html(html_text, page_url))
            self._fetch_next_icon_candidate()
        finally:
            reply.deleteLater()


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
            painter.drawPixmap(14, (self.height() - 16) // 2, 16, 16, lucide_pixmap(self.icon_kind, 16, icon_color))

        text_rect = self.rect().adjusted(text_left, 0, -28, 0)
        painter.setPen(QColor(text_color))
        painter.drawText(text_rect, Qt.AlignVCenter | Qt.AlignLeft, self.currentText())

        arrow_color = ICON_HOVER_COLOR if enabled and hovered else (ICON_COLOR if enabled else ICON_DISABLED_COLOR)
        painter.drawPixmap(self.width() - 22, (self.height() - 14) // 2, 14, 14, lucide_pixmap("chevron-down", 14, arrow_color))

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
    _network_manager = None

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ThumbBox")
        self.setFixedSize(THUMBNAIL_WIDTH, 54)
        self.icon = LucideIconWidget("video", size=24, color="#94A3B8", parent=self)
        self.thumbnail_url = ""
        self._pixmap = QPixmap()
        self._reply = None

    @classmethod
    def network_manager(cls):
        if cls._network_manager is None:
            cls._network_manager = QNetworkAccessManager()
        return cls._network_manager

    def set_thumbnail_url(self, url, referer=""):
        url = str(url or "").strip()
        if url == self.thumbnail_url:
            return
        self.thumbnail_url = url
        self._pixmap = QPixmap()
        self.icon.show()
        if self._reply:
            self._reply.abort()
            self._reply = None
        if not url:
            self.update()
            return
        parsed = QUrl.fromUserInput(url)
        if parsed.isLocalFile():
            self._set_pixmap(QPixmap(parsed.toLocalFile()))
            return
        if parsed.scheme() not in {"http", "https"}:
            self.update()
            return
        request = QNetworkRequest(parsed)
        if referer:
            request.setRawHeader(b"Referer", str(referer).encode("utf-8"))
        self._reply = self.network_manager().get(request)
        self._reply.finished.connect(self._thumbnail_finished)

    def _thumbnail_finished(self):
        reply = self._reply
        self._reply = None
        if not reply:
            return
        try:
            if reply.error() == QNetworkReply.NoError:
                pixmap = QPixmap()
                if pixmap.loadFromData(reply.readAll()):
                    self._set_pixmap(pixmap)
        finally:
            reply.deleteLater()

    def _set_pixmap(self, pixmap):
        if pixmap.isNull():
            self.icon.show()
            self.update()
            return
        self._pixmap = pixmap
        self.icon.hide()
        self.update()

    def resizeEvent(self, event):
        self.icon.move((self.width() - self.icon.width()) // 2, (self.height() - self.icon.height()) // 2)
        super().resizeEvent(event)

    def paintEvent(self, event):
        super().paintEvent(event)
        if self._pixmap.isNull():
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.SmoothPixmapTransform)
        scaled = self._pixmap.scaled(self.size(), Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
        x = (self.width() - scaled.width()) // 2
        y = (self.height() - scaled.height()) // 2
        painter.drawPixmap(x, y, scaled)


class PrimaryActionButton(QPushButton):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._loading = False
        self._angle = 0
        self._timer = QTimer(self)
        self._timer.setInterval(70)
        self._timer.timeout.connect(self._advance)

    def set_loading(self, loading):
        loading = bool(loading)
        if self._loading == loading:
            return
        self._loading = loading
        if loading:
            self._timer.start()
        else:
            self._timer.stop()
            self._angle = 0
        self.update()

    def is_loading(self):
        return self._loading

    def _advance(self):
        self._angle = (self._angle - 28) % 360
        self.update()

    def paintEvent(self, event):
        super().paintEvent(event)
        if not self._loading:
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        pen = QPen(QColor("#FFFFFF"), 2)
        pen.setCapStyle(Qt.RoundCap)
        painter.setPen(pen)
        size = 14
        rect = QRectF(20, (self.height() - size) / 2, size, size)
        painter.drawArc(rect, int(self._angle * 16), int(270 * 16))
