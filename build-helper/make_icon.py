"""Generate ClipFlow icon files from the app icon asset/runtime icon."""

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from tools.clipflow_theme import _app_icon_asset_path, configure_app_font, create_app_icon


def _write_qt_master_png(master_png):
    app = QApplication(sys.argv)
    configure_app_font(app)
    icon = create_app_icon(1024)
    pixmap = icon.pixmap(1024, 1024)
    pixmap.save(str(master_png), "PNG")
    app.quit()


def _load_master_image():
    from PIL import Image

    asset = _app_icon_asset_path()
    if asset.exists():
        return Image.open(asset).convert("RGBA")

    out_dir = Path(__file__).resolve().parent
    master_png = out_dir / "ClipFlow-master.png"
    _write_qt_master_png(master_png)
    try:
        return Image.open(master_png).convert("RGBA")
    finally:
        if master_png.exists():
            master_png.unlink()


def main():
    from PIL import Image

    out_dir = Path(__file__).resolve().parent
    master = _load_master_image()
    iconset = out_dir / "ClipFlow.iconset"
    iconset.mkdir(exist_ok=True)

    # (filename, pixel size)
    specs = [
        ("icon_16x16.png", 16),
        ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32),
        ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128),
        ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256),
        ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512),
        ("icon_512x512@2x.png", 1024),
    ]
    for name, size in specs:
        resized = master.resize((size, size), Image.LANCZOS)
        resized.save(iconset / name, "PNG")

    try:
        ico = out_dir / "ClipFlow.ico"
        master.save(
            str(ico),
            format="ICO",
            sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
        )
        print("wrote", ico)
    except Exception as exc:  # noqa: BLE001 - icon generation is best-effort
        print("skip .ico:", exc)

    try:
        icns = out_dir / "ClipFlow.icns"
        master.save(
            str(icns),
            format="ICNS",
            sizes=[(16, 16), (32, 32), (64, 64), (128, 128), (256, 256), (512, 512), (1024, 1024)],
        )
        print("wrote", icns)
    except Exception as exc:  # noqa: BLE001 - icon generation is best-effort
        print("skip .icns:", exc)

    if sys.platform == "darwin":
        try:
            icns = out_dir / "ClipFlow.icns"
            subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns)], check=True)
            print("refreshed", icns)
        except Exception as exc:  # noqa: BLE001 - best-effort macOS refinement
            print("skip iconutil .icns:", exc)


if __name__ == "__main__":
    main()
