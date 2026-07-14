# TASK-01 공통 기반 구축

## 목적

음식 사진 분석, 음성 명령, TTS를 기존 DAT 스트림 생명주기에 안전하게 연결할 공통 기반을 만든다. Android 및 네트워크 구현은 인터페이스 뒤로 분리해 단위 테스트에서 fake 구현을 주입할 수 있게 한다.

## 선행 조건

- 기존 `StreamViewModel`과 `StreamScreen`의 수동 촬영 흐름이 동작해야 한다.
- DAT 0.8.0의 core, camera, mockdevice 의존성이 연결되어 있어야 한다.

## 구현 범위

1. 권한 및 빌드 설정
   - `RECORD_AUDIO` 선언과 런타임 권한 요청 흐름을 추가한다.
   - 음성 인식 기능 지원 여부를 확인하고 미지원/권한 거부를 사용자 메시지로 구분한다.
   - HTTP 클라이언트, JSON 변환, 코루틴 테스트 의존성을 버전 카탈로그에 추가한다.
   - `local.properties`의 `analysis_server_url`을 debug `BuildConfig`에 주입한다. 개인 IP나 비밀값은 커밋하지 않는다.
   - debug에서만 사설망 cleartext HTTP를 허용하고 release는 HTTPS만 허용한다.

2. 분석 클라이언트와 모델
   - `FoodAnalysisClient`, `HttpFoodAnalysisClient`, 요청/응답 DTO, typed error를 추가한다.
   - `POST /v1/food-analysis`에 JPEG를 `photo` multipart 필드로 전송한다.
   - 연결/읽기 timeout, 취소, HTTP 4xx/5xx, 잘못된 JSON, 음식 미검출을 서로 다른 오류로 매핑한다.
   - 응답의 `recipeText`를 표시/TTS 전에 500자로 제한한다.

3. 음성 인터페이스
   - `SpeechRecognitionController`와 Android `SpeechRecognizer` 구현을 추가한다.
   - `TextToSpeechController`와 Android `TextToSpeech` 구현을 추가한다.
   - 시작, 중지, 완료, 오류, 해제 동작을 인터페이스 계약에 포함한다.

4. 화면 상태와 UI
   - `StreamUiState`에 `recognizedText`, `voiceState`, `foodAnalysisState`, `foodName`, `recipeText`, `userMessage`, `isSpeaking`을 추가한다.
   - 인식 문구, 촬영/업로드 상태, 결과, 재시도 가능한 오류를 `StreamScreen`에 표시한다.
   - 이미지 압축과 네트워크 호출은 메인 스레드 밖에서 수행한다.

## 오류 및 로그 정책

- 사진 캡처 성공은 분석 요청 성공으로 간주하지 않는다. 캡처 성공 후 업로드 단계로 상태를 전환하고, 분석 실패 시 캡처 결과와 별도로 `foodAnalysisState = Error`를 표시한다.
- 캡처 실패 메시지는 "사진을 촬영하지 못했습니다"처럼 촬영 단계임을 명시한다.
- 분석 실패 메시지는 timeout, 서버 오류, 음식 미검출 등 사용자가 취할 수 있는 조치에 맞게 구분한다.
- logcat에는 단계(`capture`, `upload`, `parse`), `requestId`, HTTP 상태, 처리 시간과 typed error만 기록한다.
- 사진 바이트, 조리법 전문, 인증정보 및 개인 네트워크 정보는 로그에 남기지 않는다.

## 완료 기준

- debug 빌드에서 분석 서버 주소가 주입되고 release 빌드에는 cleartext 허용이 없다.
- 분석, STT, TTS가 인터페이스로 분리되어 fake 구현을 주입할 수 있다.
- 촬영 성공 후 분석 실패가 서로 다른 상태와 사용자 메시지로 표현된다.
- 권한 거부와 기능 미지원 상태가 앱 종료 없이 표시된다.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

