# 음식 인식 카메라 앱 구현 계획

## 1. 목표와 완료 기준

Meta Wearables DAT SDK의 CameraAccess 샘플을 기반으로 다음 사용자 흐름을 구현한다.

1. 스트리밍 화면에 진입하면 음성 인식을 시작한다.
2. 사용자가 "사진찍어줘"라고 말하면 화면의 촬영 버튼을 누른 것과 동일한 촬영 파이프라인을 실행한다.
3. 최근 STT 인식 문구와 현재 처리 상태를 스마트폰 화면에 표시한다.
4. 촬영 이미지를 로컬 네트워크의 분석 서버에 전송하고 음식명과 500자 이내 조리법을 받는다.
5. 결과를 스마트폰 화면에 표시한 뒤 Android TTS로 읽는다.
6. TTS 재생 중에는 STT를 중지하고, 재생 완료 또는 오류 후 스트리밍이 유효하면 자동으로 다시 듣는다.

완료 조건은 MockDeviceKit에서 전체 흐름을 재현한 뒤 실제 Meta Ray-Ban 기기에서도 같은 흐름, 오류 복구, 생명주기 처리가 동작하는 것이다.

## 2. 현재 기준선

- `StreamViewModel`이 DAT 세션/스트림 생성, 프레임 수신, 사진 촬영을 담당한다.
- `StreamScreen`에 수동 촬영 버튼이 있고 `Stream.capturePhoto()` 결과를 처리한다.
- MockDeviceKit에서 Ray-Ban Meta 페어링, 전원/착용 상태, 영상 피드 및 촬영 이미지를 설정할 수 있다.
- DAT 0.8.0의 `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` 의존성이 연결되어 있다.
- 분석 서버 주소와 음성 명령 문구를 `BuildConfig`로 주입할 기반이 있다.

기존 DAT 세션과 스트림 생명주기는 유지하고, 음성/분석/TTS 기능은 Android 및 네트워크 구현 세부사항을 인터페이스 뒤에 분리한다.

## 3. 제안 구조

```text
cameraaccess/
├── analysis/
│   ├── FoodAnalysisClient.kt       # 앱이 사용하는 분석 인터페이스
│   ├── HttpFoodAnalysisClient.kt   # 사설 HTTP 서버 호출 구현
│   └── FoodAnalysisModels.kt       # 요청/응답 및 오류 모델
├── speech/
│   ├── SpeechRecognitionController.kt
│   ├── AndroidSpeechRecognitionController.kt
│   ├── TextToSpeechController.kt
│   └── AndroidTextToSpeechController.kt
├── stream/
│   ├── StreamViewModel.kt          # 촬영 파이프라인과 화면 상태 조정
│   └── StreamUiState.kt
└── ui/
    └── StreamScreen.kt
```

- `StreamViewModel`을 단일 상태 소유자로 두고 `StateFlow<StreamUiState>`로 UI를 갱신한다.
- Android `SpeechRecognizer`와 `TextToSpeech`, HTTP 클라이언트를 인터페이스로 감싸 테스트 대역을 주입할 수 있게 한다.
- 수동 버튼과 음성 명령은 모두 하나의 `requestCapture(source)` 함수로 들어가 중복 촬영 방지, 촬영, 분석, 결과 표시를 동일하게 처리한다.
- 무거운 이미지 압축과 네트워크 처리는 메인 스레드 밖에서 수행한다.

## 4. 상태와 동작 규칙

`StreamUiState`에 다음 상태를 추가한다.

- `recognizedText`: 화면에 표시할 최근 STT 문구
- `voiceState`: `Stopped`, `Listening`, `SuspendedForTts`, `Unavailable`, `Error`
- `foodAnalysisState`: `Idle`, `Capturing`, `Uploading`, `Success`, `Error`
- `foodName`, `recipeText`, `userMessage`
- `isSpeaking`: TTS 재생 여부

동작 규칙은 다음과 같다.

- 듣기는 화면이 foreground이고 `StreamState.STREAMING`일 때만 유지한다.
- 부분 인식 결과도 UI에 표시하되 촬영 명령은 최종 결과에서 판정한다.
- 명령 판정 시 공백과 기본 문장부호를 제거해 "사진 찍어줘"와 "사진찍어줘"를 허용한다.
- 한 번 인식한 명령에는 cooldown과 `isCapturing` 가드를 적용해 중복 콜백으로 인한 연속 촬영을 막는다.
- 촬영/분석 중 추가 명령은 무시하고 처리 중 상태를 표시한다.
- TTS 시작 직전에 STT 세션을 종료하고 `UtteranceProgressListener.onDone/onError`에서 조건부로 재시작한다.
- 화면 이탈, 스트림 중지, 세션 종료 시 STT/TTS/분석 작업을 취소하고 리소스를 해제한다.
- 일시적인 STT timeout/no-match는 짧은 지연 후 재시작하되 권한 거부와 기능 미지원은 사용자 조치가 필요한 오류로 구분한다.

## 5. 분석 서버 연동 계약

앱은 OpenAI API 키나 OpenAI 엔드포인트를 포함하지 않는다. OpenAI 호출과 프롬프트, 모델 선택은 분석 서버만 담당한다.

```http
POST /v1/food-analysis
Content-Type: multipart/form-data

photo: image/jpeg
```

```json
{
  "foodName": "김치볶음밥",
  "recipeText": "재료와 조리 순서를 포함한 500자 이내 설명",
  "requestId": "server-generated-id"
}
```

- 개발 주소는 `local.properties`의 `analysis_server_url=http://192.168.x.x:8080/`처럼 주입하고 소스에 개인 IP를 커밋하지 않는다.
- cleartext HTTP 허용은 debug 빌드와 로컬 사설망 개발에만 한정한다. release 빌드는 HTTPS만 허용하도록 manifest/network security 설정을 분리한다.
- 촬영 이미지는 방향을 보정한 JPEG로 변환하고 합리적인 해상도와 품질로 압축해 multipart로 보낸다.
- 연결/읽기 timeout, 취소, 4xx/5xx, 잘못된 JSON, 음식 미검출을 typed error로 변환해 UI에 표시한다.
- 서버가 500자 제한을 보장하고 앱에서도 응답 길이를 검증해 500자를 넘는 내용은 표시/TTS 전에 안전하게 제한한다.
- 로그에는 `requestId`, HTTP 상태, 처리 시간만 남기고 사진 바이트, 조리법 전문, 인증정보는 기록하지 않는다.

## 6. 구현 단계

### Phase A. 공통 기반

- [ ] `RECORD_AUDIO` 런타임 권한과 음성 인식 지원 여부 확인 흐름을 추가한다.
- [ ] HTTP 클라이언트, JSON 변환, 코루틴 테스트 의존성을 버전 카탈로그에 추가한다.
- [ ] debug 전용 사설 HTTP 주소 주입과 cleartext 정책을 구성하고 release에서 차단되는지 확인한다.
- [ ] 분석 서버 DTO, 클라이언트 인터페이스, 오류 모델을 구현한다.
- [ ] STT/TTS 인터페이스와 Android 구현을 추가한다.
- [ ] `StreamUiState`와 `StreamScreen`에 인식 문구, 처리 상태, 음식명, 조리법, 재시도 가능한 오류 UI를 추가한다.

### Phase B. MockDeviceKit 우선 구현

- [ ] Fake STT에 인식 문구를 주입해 명령 정규화, 중복 방지, 수동/음성 공통 촬영 경로를 검증한다.
- [ ] MockDeviceKit에 음식 테스트 영상과 촬영 이미지를 설정한다.
- [ ] `capturePhoto()` 결과가 JPEG 변환 후 분석 클라이언트로 한 번만 전달되는지 검증한다.
- [ ] MockWebServer 또는 fake 분석 클라이언트로 성공, timeout, 서버 오류, 음식 미검출, 500자 초과 응답을 검증한다.
- [ ] Fake TTS로 `Listening -> SuspendedForTts -> Listening` 전이를 결정적으로 검증한다.
- [ ] 계측 테스트에서 mock 안경을 `powerOn -> unfold -> don`한 뒤 스트리밍, 음성 촬영, 결과 표시까지 확인한다.
- [ ] 화면 이탈, 스트림 종료, TTS 오류 뒤에 마이크와 코루틴 작업이 남지 않는지 확인한다.

### Phase C. 로컬 분석 서버 통합

- [ ] 개발 PC와 테스트 폰을 같은 네트워크에 연결하고 서버를 `0.0.0.0`에 바인딩한다.
- [ ] 방화벽에서 지정 포트를 허용하고 폰 브라우저 또는 진단 요청으로 사설 IP 접근을 확인한다.
- [ ] 분석 서버가 이미지 MIME/크기 검증, OpenAI 호출, 500자 제한, 오류 매핑을 수행하도록 한다.
- [ ] 네트워크 지연 중 진행 상태와 취소가 올바르게 표시되는지 확인한다.
- [ ] 앱 APK/로그에 OpenAI API 키가 포함되지 않았는지 검사한다.

### Phase D. 실제 스마트글라스 확장

- [ ] Meta AI 앱의 Developer Mode, 앱 등록, Bluetooth 및 DAT 카메라 권한을 확인한다.
- [ ] 실제 기기에서 등록 후 세션이 `STARTED`, 스트림이 `STREAMING`에 도달하는지 확인한다.
- [ ] 안경으로 촬영한 HEIC/Bitmap의 방향, 색상, 업로드 이미지 품질을 확인한다.
- [ ] 착용 해제, 접기, Bluetooth 단절, 세션 `PAUSED/STOPPED`, 스트림 오류 뒤 복구를 검증한다.
- [ ] 주변 소음과 TTS 스피커 출력 환경에서 오인식, 자기 음성 재인식, 재시작 지연을 조정한다.
- [ ] 실제 네트워크에서 촬영부터 결과 표시/TTS 완료까지 지연 시간을 측정한다.

## 7. 테스트 전략

### 로컬 단위 테스트

- 명령 정규화 및 일치/불일치
- cooldown과 동시 요청 시 단일 촬영 보장
- 스트림 상태와 STT/TTS 상태 전이
- 서버 응답 파싱, 오류 매핑, 500자 제한
- ViewModel 취소 및 재시도 동작

### MockDeviceKit 계측 테스트

- 페어링/전원/펼침/착용 후 스트리밍 시작
- 수동 버튼과 Fake STT 명령이 동일한 촬영 함수를 호출
- mock 이미지가 분석 요청에 포함되고 결과가 UI에 표시
- TTS 중 STT 중지 및 완료 후 재개
- 권한 거부, 캡처 실패, 서버 timeout, 세션 종료 UI

시스템 `SpeechRecognizer` 자체의 인식 품질은 환경 의존적이므로 자동화 테스트에서는 fake로 상태 전이를 검증하고, 실제 마이크 인식은 수동 테스트로 분리한다.

### 실제 기기 수동 테스트

- 조용한 환경과 생활 소음 환경에서 명령 인식
- 수동/음성 촬영 결과 일치
- 연속 명령 및 TTS 재생 중 발화 처리
- 음식/비음식/여러 음식 이미지 분석
- Wi-Fi 단절, 서버 중단, 장시간 스트리밍, 앱 background/foreground 전환

## 8. 품질 게이트

각 구현 단계에서 다음 명령을 통과시킨다.

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest`는 에뮬레이터 또는 Android 기기가 연결된 환경에서 실행한다. 실제 안경 단계는 자동화 결과와 별도로 수동 테스트 기록을 남긴다.

## 9. 사용자 인수 테스트 시나리오

1. 앱에서 MockDeviceKit을 활성화하고 Ray-Ban Meta를 페어링한다.
2. mock 안경을 `Power On`, `Unfold`, `Don` 순서로 전환한다.
3. 테스트 영상과 음식 촬영 이미지를 지정하고 스트리밍 화면에 진입한다.
4. 마이크 권한을 허용하고 화면에 듣기 상태가 표시되는지 확인한다.
5. "사진찍어줘"라고 말해 인식 문구, 촬영, 업로드 진행 상태가 순서대로 표시되는지 확인한다.
6. 음식명과 500자 이내 조리법이 표시되고 TTS로 읽히는지 확인한다.
7. TTS 중 STT가 멈추고 TTS 완료 후 듣기가 자동 재개되는지 확인한다.
8. 촬영 버튼으로도 동일한 분석/TTS 결과 흐름이 실행되는지 확인한다.
9. 분석 서버를 중지해 오류와 재시도 동작을 확인한 뒤 서버를 복구한다.
10. MockDeviceKit 검증 완료 후 같은 시나리오를 실제 Meta Ray-Ban 기기로 반복한다.

## 10. 범위 밖 항목

- 앱에서 OpenAI API 직접 호출 또는 API 키 저장
- 로컬 개발 외 환경에서 cleartext HTTP 허용
- 스마트글라스 디스플레이에 조리법 렌더링
- 음식 분석 이력 동기화, 사용자 계정, 클라우드 배포
