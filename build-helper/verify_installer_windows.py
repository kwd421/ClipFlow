"""Exercise the real NSIS installer in an isolated per-user install directory."""

import argparse
import ctypes
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
import winreg


def run(executable, *args, expected=0):
    command = [str(executable), *map(str, args)]
    # NSIS directory overrides must be last and unquoted, including paths with spaces.
    if command[-1].startswith(("/D=", "_?=")):
        command = subprocess.list2cmdline(command[:-1]) + " " + command[-1]
    result = subprocess.run(command, timeout=90, capture_output=True)
    if result.returncode != expected:
        raise AssertionError((result.returncode, result.stdout, result.stderr))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--makensis", default=os.environ.get("CLIPFLOW_MAKENSIS") or shutil.which("makensis")
                        or str(Path(os.environ["ProgramFiles(x86)"]) / "NSIS/makensis.exe"))
    args = parser.parse_args()
    product = "ClipFlowInstallerTest-" + uuid.uuid4().hex
    script = Path(__file__).with_name("ClipFlow.nsi")
    install_key = rf"Software\{product}\Installer"
    uninstall_key = rf"Software\Microsoft\Windows\CurrentVersion\Uninstall\{product}"
    shortcut = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Start Menu/Programs" / f"{product}.lnk"
    with tempfile.TemporaryDirectory(prefix="clipflow-installer-") as folder:
        root = Path(folder)
        target = root / "custom path 검증"
        payload = root / "payload.exe"
        compiler = Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
        source = root / "payload.cs"
        installers = []
        for version in ("1.0.0", "1.0.1"):
            source.write_text(
                'class Probe { static void Main() { System.IO.File.WriteAllText('
                'System.IO.Path.Combine(System.AppDomain.CurrentDomain.BaseDirectory, "started.txt"), '
                f'"{version}|" + System.Environment.GetEnvironmentVariable('
                '"PYINSTALLER_RESET_ENVIRONMENT")); } }', encoding="utf-8")
            run(compiler, "/nologo", "/target:winexe", f"/out:{payload}", source)
            installer = root / f"install-{version}.exe"
            run(args.makensis, "/V2", f"/DPRODUCT_NAME={product}", f"/DAPP_VERSION={version}",
                f"/DAPP_EXE={payload}", f"/DOUTPUT_FILE={installer}", script)
            installers.append(installer)
        try:
            run(installers[0], "/S", f"/D={target}")
            installed = target / "ClipFlow.exe"
            old_bytes = installed.read_bytes()
            user_video = target / "user-video.mp4"
            user_video.write_bytes(b"user data must survive")
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, rf"Software\{product}\Settings") as key:
                winreg.SetValueEx(key, "history", 0, winreg.REG_SZ, "keep me")
            assert shortcut.is_file()

            # A sharing violation must not truncate the old executable or mark the new version installed.
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32,
                                          ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
            kernel.CreateFileW.restype = ctypes.c_void_p
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            handle = kernel.CreateFileW(str(installed), 0x80000000, 0, None, 3, 0, None)
            assert handle not in (None, ctypes.c_void_p(-1).value)
            try:
                run(installers[1], "/S", "/UPDATE", expected=2)
            finally:
                kernel.CloseHandle(handle)
            assert installed.read_bytes() == old_bytes
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, uninstall_key) as key:
                assert winreg.QueryValueEx(key, "DisplayVersion")[0] == "1.0.0"
            assert not (target / "ClipFlow.update").exists()
            print("locked update: old version preserved", flush=True)

            # No /D on upgrade: the installer must recover the custom path from its registration.
            run(installers[1], "/UPDATE")
            started = target / "started.txt"
            deadline = time.monotonic() + 10
            while not started.exists() and time.monotonic() < deadline:
                time.sleep(0.1)
            assert started.read_text() == "1.0.1|1"
            assert installed.read_bytes() == payload.read_bytes()
            assert not (target / "ClipFlow.previous").exists()
            assert user_video.read_bytes() == b"user data must survive"
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, uninstall_key) as key:
                assert winreg.QueryValueEx(key, "DisplayVersion")[0] == "1.0.1"
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, rf"Software\{product}\Settings") as key:
                assert winreg.QueryValueEx(key, "history")[0] == "keep me"
            print("upgrade: new version launched; custom path, settings and media verified", flush=True)

            # _?= keeps the uninstaller in this test directory and lets us wait for its real completion.
            run(target / "Uninstall.exe", "/S", f"_?={target}")
            assert not installed.exists()
            assert user_video.exists()
            assert not shortcut.exists()
            print("uninstall: user media preserved", flush=True)
        finally:
            for key in (install_key, uninstall_key, rf"Software\{product}\Settings", rf"Software\{product}"):
                try:
                    winreg.DeleteKey(winreg.HKEY_CURRENT_USER, key)
                except FileNotFoundError:
                    pass
            shortcut.unlink(missing_ok=True)
    print("installer verification passed")


if __name__ == "__main__":
    main()
