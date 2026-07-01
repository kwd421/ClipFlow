# Universal MP4 Browser Downloader

URL을 붙여넣으면 yt-dlp로 영상 후보를 분석하고, 원하는 품질을 골라 MP4/WEBM/WAV로 저장하는 간단한 GUI 앱입니다.

## 기능

- URL 분석 후 같은 영상은 한 줄로 묶고 품질은 드롭다운으로 선택합니다.
- 후보는 썸네일, 제목, 길이, 확장자, 품질, 예상 크기를 보여줍니다.
- Chrome, Edge, Firefox 쿠키 읽기 옵션을 지원합니다.
- 일반 추출이 TLS/브라우저 지문 문제로 실패하면 설치된 Chrome/Edge/Chromium의 headless DOM 분석 fallback을 사용합니다.
- 다운로드 파일명은 UI에 보이는 영상 제목을 기준으로 만들고, trailing 도메인 꼬리는 제거합니다.
- DRM, CAPTCHA, 유료/비공개 권한 우회는 하지 않습니다.

## 실행

- ClipFlow (PySide6, 패키징되는 기본 앱): `python tools/clipflow_qt.py`
- 레거시 Tkinter 앱 (개발용): `python tools/url_downloader_gui.py`

## Windows 빌드

```powershell
cd path\to\universal-mp4-browser-downloader
powershell -ExecutionPolicy Bypass -File build-helper\build_windows.ps1
```

빌드 결과:

- `ClipFlow.exe`
- `dist\ClipFlow.exe`

## macOS 빌드

PyInstaller는 Windows에서 macOS 앱을 cross-build하지 못합니다. Mac에서 이 저장소를 클론한 뒤 빌드해야 합니다.

```bash
cd /path/to/universal-mp4-browser-downloader
bash build-helper/build_macos.sh
```

빌드 결과는 환경에 따라 다음 중 하나입니다.

- `dist/ClipFlow`
- `dist/ClipFlow.app`

빌드 스크립트는 macOS 키체인의 첫 번째 `Developer ID Application` 인증서를 자동으로 찾아 `dist/ClipFlow.app`을 hardened runtime으로 서명합니다. 같은 이름의 인증서가 여러 keychain에 있을 수 있으므로 자동 감지는 SHA-1 identity를 사용합니다. 다른 인증서를 쓰려면 직접 지정합니다.

```bash
CLIPFLOW_CODESIGN_IDENTITY="0123456789ABCDEF0123456789ABCDEF01234567" \
bash build-helper/build_macos.sh
```

Sparkle 자동 업데이트를 포함하려면 Sparkle 2 배포본의 `Sparkle.framework`, appcast URL, Sparkle EdDSA 공개키를 함께 지정합니다.

```bash
CLIPFLOW_SPARKLE_FRAMEWORK="/path/to/Sparkle.framework" \
CLIPFLOW_SPARKLE_FEED_URL="https://example.com/clipflow/appcast.xml" \
CLIPFLOW_SPARKLE_PUBLIC_ED_KEY="base64-public-ed-key" \
bash build-helper/build_macos.sh
```

`CLIPFLOW_VERSION`과 `CLIPFLOW_BUILD_NUMBER`를 지정하면 Sparkle이 비교할 `CFBundleShortVersionString`과 `CFBundleVersion`에 반영됩니다.

notarization 인증 정보가 있으면 빌드 스크립트는 `dist/ClipFlow-notary.zip`을 Apple notary service에 제출하고, 승인 후 `dist/ClipFlow.app`에 ticket을 staple한 뒤 `spctl`로 Gatekeeper 판정을 확인합니다. 키체인 프로필을 쓰는 방식이 가장 간단합니다.

```bash
xcrun notarytool store-credentials clipflow-notary

CLIPFLOW_NOTARY_PROFILE="clipflow-notary" \
bash build-helper/build_macos.sh
```

App Store Connect API key를 직접 지정할 수도 있습니다.

```bash
CLIPFLOW_NOTARY_KEY="/path/to/AuthKey_XXXXXXXXXX.p8" \
CLIPFLOW_NOTARY_KEY_ID="XXXXXXXXXX" \
CLIPFLOW_NOTARY_ISSUER="00000000-0000-0000-0000-000000000000" \
bash build-helper/build_macos.sh
```

Apple ID와 앱 전용 암호 방식도 지원합니다.

```bash
CLIPFLOW_NOTARY_APPLE_ID="name@example.com" \
CLIPFLOW_NOTARY_TEAM_ID="TEAMID12345" \
CLIPFLOW_NOTARY_PASSWORD="app-specific-password" \
bash build-helper/build_macos.sh
```

`CLIPFLOW_NOTARIZE=1`을 지정하면 notarization 인증 정보가 없을 때 빌드가 실패합니다.

macOS에서 TLS/브라우저 지문 fallback까지 쓰려면 Chrome, Edge, 또는 Chromium 중 하나가 설치되어 있어야 합니다. 직접 경로를 지정하려면 `UMP4_BROWSER_PATH` 환경변수를 사용하세요.

## 개발 검증

```bash
python -m unittest discover -s test -p "test_*.py" -v
```

## 배포 참고

Windows SmartScreen과 macOS Gatekeeper 경고는 코드 서명과 notarization 없이는 완전히 제거할 수 없습니다. macOS 공개 배포에서는 서명된 `.app` 또는 DMG를 notarization까지 완료해야 합니다.
