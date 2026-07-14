# TASK-01 공통 기반 체크리스트

## 권한 및 설정

- [x] `RECORD_AUDIO` 선언과 런타임 권한 요청이 구현되었다.
- [x] 음성 인식 미지원과 권한 거부가 다른 사용자 메시지로 표시된다.
- [x] HTTP, JSON, 코루틴 테스트 의존성이 버전 카탈로그에 정의되었다.
- [x] 분석 서버 주소가 `local.properties`에서 debug `BuildConfig`로 주입된다.
- [x] 개인 IP와 비밀값이 저장소에 커밋되지 않았다.
- [x] debug에서만 사설망 cleartext HTTP가 허용되고 release에서는 차단된다.

## 구조 및 동작

- [x] 분석 DTO, 클라이언트 인터페이스, HTTP 구현, typed error가 추가되었다.
- [x] timeout, 취소, 4xx/5xx, 잘못된 JSON, 음식 미검출이 구분된다.
- [x] 조리법이 표시/TTS 전에 500자로 제한된다.
- [x] STT/TTS 인터페이스와 Android 구현이 분리되었다.
- [x] `StreamUiState`와 화면에 인식 문구, 처리 상태, 결과, 오류가 표시된다.
- [x] 이미지 및 네트워크 처리가 메인 스레드 밖에서 실행된다.

## 오류 및 로그

- [x] 캡처 성공과 분석 요청 성공/실패가 별도 상태로 관리된다.
- [x] 캡처 실패와 분석 실패의 사용자 메시지가 명확히 다르다.
- [x] logcat에서 `capture`, `upload`, `parse` 단계를 구분할 수 있다.
- [x] 사진 바이트, 조리법 전문, 인증정보가 로그에 남지 않는다.

## 품질 게이트

- [x] `.\gradlew.bat test` 통과
- [x] `.\gradlew.bat lint` 통과
- [x] `.\gradlew.bat assembleDebug` 통과
