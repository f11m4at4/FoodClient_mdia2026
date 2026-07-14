# TASK-03 로컬 분석 서버 통합

## 목적

개발 PC의 분석 서버와 Android 앱을 사설 네트워크로 연결하고 실제 HTTP 응답, 지연, 실패, 보안 경계를 검증한다.

## 선행 조건

- TASK-02의 fake 분석 클라이언트 기반 전체 흐름이 통과해야 한다.
- 분석 서버가 `POST /v1/food-analysis` 계약을 구현해야 한다.

## 구현 범위

1. 네트워크 연결
   - 개발 PC와 테스트 폰을 같은 네트워크에 연결한다.
   - 서버를 `0.0.0.0`에 바인딩하고 방화벽에서 지정 포트만 허용한다.
   - 폰 브라우저 또는 진단 엔드포인트로 사설 IP 접근을 먼저 확인한다.
   - `local.properties`에 개발 서버 주소를 설정하고 소스에는 개인 IP를 기록하지 않는다.

2. 서버 계약 검증
   - JPEG multipart 필드명, MIME, 파일 크기 제한을 확인한다.
   - 서버가 음식명, 500자 이내 조리법, `requestId`를 반환하는지 확인한다.
   - 서버 오류 응답을 앱의 typed error와 사용자 메시지로 매핑한다.
   - OpenAI 호출, 프롬프트와 모델 선택, API 키 저장은 서버에서만 수행한다.

3. 실패 및 관측성
   - 지연 중 `Uploading` 상태와 취소 동작을 확인한다.
   - 캡처 성공 logcat 다음에 분석 요청 시작/실패 로그가 별도 단계로 남는지 확인한다.
   - timeout, 서버 중단, 4xx/5xx, 잘못된 JSON, 음식 미검출을 각각 재현한다.
   - 사용자는 촬영 실패와 분석 실패를 혼동하지 않는 메시지를 보고 재시도할 수 있어야 한다.
   - 로그에는 `requestId`, HTTP 상태, 처리 시간만 남긴다.

4. 보안 확인
   - APK, BuildConfig, 소스 및 logcat에 OpenAI API 키가 없는지 검사한다.
   - release manifest/network security 정책에서 cleartext HTTP가 차단되는지 확인한다.

## 완료 기준

- 테스트 폰에서 실제 음식 사진을 서버로 보내 결과를 표시하고 TTS로 읽는다.
- 서버 중단과 복구 뒤 앱 재시도가 정상 동작한다.
- 캡처 성공과 후속 분석 실패가 UI 및 logcat에서 별도 사건으로 확인된다.
- 앱과 APK에 OpenAI API 키가 포함되지 않는다.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

자동 검증 후 서버 정상, timeout, 중단 시나리오를 테스트 폰에서 수동으로 기록한다.

