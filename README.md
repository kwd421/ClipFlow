# ClipFlow

ClipFlow는 영상 링크를 붙여넣고 품질을 고른 뒤 바로 저장할 수 있는 데스크톱 다운로드 앱입니다.

## 할 수 있는 일

- 영상 링크 분석 후 화질 선택
- MP4, WEBM, MP3, WAV, AAC 저장
- 시작/종료 시간을 지정한 구간 다운로드
- YouTube 링크에서 단일 영상 / 재생목록 선택
- Chrome, Edge, Firefox, Safari 등 브라우저 쿠키 사용

## 다운로드

- 최신 배포본: [Releases](https://github.com/kwd421/universal-mp4-browser-downloader/releases)
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

```powershell
python tools/clipflow_qt.py
```
