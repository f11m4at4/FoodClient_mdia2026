# TASK-02 MockDeviceKit 전체 흐름 구현

## 목적

MockDeviceKit과 fake 의존성으로 음성 명령부터 촬영, 분석 결과 표시, TTS, 듣기 재개까지의 전체 흐름을 반복 가능하게 구현하고 자동화한다.

## 선행 조건

- TASK-01이 완료되어 분석, STT, TTS 인터페이스와 화면 상태가 준비되어 있어야 한다.

## 구현 범위

1. 공통 촬영 파이프라인
   - 수동 버튼과 음성 명령을 `requestCapture(source)` 하나로 연결한다.
   - 최종 STT 결과에서 공백과 기본 문장부호를 제거해 "사진찍어줘"를 판정한다.
   - cooldown과 촬영 진행 가드로 중복 콜백 및 동시 요청에서 한 번만 촬영한다.
   - `Capturing -> Uploading -> Success/Error` 상태 전이를 명확히 유지한다.

2. 이미지 및 분석 처리
   - `capturePhoto()` 성공 결과의 방향을 보정하고 JPEG로 압축한 뒤 분석 클라이언트에 한 번만 전달한다.
   - 캡처가 성공했다는 로그를 분석 요청 결과와 별도로 남긴다.
   - 분석 요청 실패 시 캡처 성공 상태를 덮어쓰지 않고 분석 단계 오류, 재시도 안내, logcat 원인을 제공한다.
   - 성공 결과를 화면에 표시하고 TTS로 읽는다.

3. 음성 및 생명주기
   - 화면이 foreground이고 스트림이 `STREAMING`일 때만 STT를 유지한다.
   - TTS 시작 직전에 STT를 중지하고 완료 또는 오류 후 조건이 유효할 때만 재시작한다.
   - 화면 이탈, 스트림 종료, 세션 종료 시 STT/TTS/분석 작업을 취소하고 리소스를 해제한다.
   - 일시적인 timeout/no-match와 권한 거부/기능 미지원을 구분한다.

4. MockDeviceKit 및 테스트
   - mock 안경에 음식 영상과 촬영 이미지를 설정한다.
   - `powerOn -> unfold -> don` 후 스트리밍과 촬영 흐름을 계측 테스트로 검증한다.
   - fake STT, fake 분석 클라이언트, fake TTS 또는 MockWebServer로 성공과 오류 경로를 결정적으로 검증한다.

## 필수 테스트 사례

- 명령 정규화의 일치/불일치와 부분 결과 미실행
- 수동/음성 요청이 같은 촬영 함수로 진입함
- 연속 명령과 동시 요청에서도 단일 캡처만 수행함
- 캡처 실패와 캡처 성공 후 분석 timeout/서버 오류를 다른 메시지로 표시함
- 음식 미검출, 잘못된 JSON, 500자 초과 응답 처리
- `Listening -> SuspendedForTts -> Listening` 전이
- 화면 이탈, 스트림 종료, TTS 오류 후 작업과 리소스가 남지 않음

## 완료 기준

- MockDeviceKit에서 음성 및 수동 촬영 모두 동일한 분석/TTS 흐름을 완료한다.
- 캡처 성공과 분석 실패를 logcat 및 UI에서 명확히 구분할 수 있다.
- 오류 후 재시도가 가능하고 중복 촬영이나 남은 코루틴 작업이 없다.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest`는 에뮬레이터 또는 Android 기기가 연결된 환경에서 실행한다.

