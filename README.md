# ClipFlow

ClipFlow는 영상 링크를 붙여넣고 품질을 고른 뒤 바로 저장할 수 있는 데스크톱 다운로드 앱입니다.

## 할 수 있는 일

- 영상 링크 분석 후 화질 선택
- MP4, WEBM, MP3, WAV, AAC 저장
- 시작/종료 시간을 지정한 구간 다운로드
- YouTube 링크에서 단일 영상 / 재생목록 선택
- Chrome, Edge, Firefox, Safari 등 브라우저 쿠키 사용

## 다운로드

- 최신 배포본: [Releases](https://github.com/kwd421/ClipFlow/releases)
- Windows: `ClipFlow-<version>.exe`
- macOS: 릴리즈 자산에 포함된 macOS 빌드 사용

## 사용 방법

1. 다운로드할 링크를 붙여넣습니다.
2. `분석`을 눌러 후보를 불러옵니다.
3. 원하는 화질과 포맷을 고릅니다.
4. 필요하면 저장 위치나 구간 다운로드 시간을 설정합니다.
5. `다운로드`를 눌러 저장합니다.

## 참고

- 로그인 필요한 영상은 쿠키를 선택해야 할 수 있습니다.
- DRM, CAPTCHA, 유료/비공개 권한 우회는 지원하지 않습니다.
- Windows SmartScreen 또는 macOS Gatekeeper 경고가 처음 보일 수 있습니다.

## 개발용 실행

### Windows 설치형 배포

Windows 릴리즈 스크립트는 NSIS가 필요합니다. `CLIPFLOW_MAKENSIS`로
`makensis.exe` 경로를 지정할 수 있습니다. `dist/ClipFlow.exe`는 단독 실행용이고,
서명 및 appcast 배포 대상인 `dist/ClipFlow-<version>.exe`는 설치 프로그램입니다.

기본 설치 위치는 `%LOCALAPPDATA%\Programs\ClipFlow`이며 업데이트는 기존 설치 경로를
유지합니다. 이전 단독 EXE 배포본 사용자는 설치형으로 한 번 전환한 뒤 시작 메뉴의
ClipFlow를 사용해야 합니다. 예전 EXE를 계속 실행하면 예전 버전이 실행됩니다.
설치와 제거는 다운로드 영상 및 앱 설정/히스토리를 삭제하지 않습니다.

릴리즈 전에 `build-helper/verify_installer_windows.py`가 임시 경로에서 설치, 잠금 실패,
버전 교체, 재실행, 사용자 파일 보존을 검사합니다. 공개 배포는 별도의 릴리즈가 필요합니다.

### 소스 실행

```powershell
python tools/clipflow_qt.py
```
