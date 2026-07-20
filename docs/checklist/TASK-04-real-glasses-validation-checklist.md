# TASK-04 실제 Meta Ray-Ban 검증 체크리스트

## 환경 및 연결

- [ ] Meta AI Developer Mode와 앱 등록 상태를 확인했다.
- [ ] Bluetooth 및 DAT 카메라 권한을 허용했다.
- [ ] 세션이 `STARTED`, 스트림이 `STREAMING`에 도달했다.

### 실행 기록 (2026-07-20)

- 실제 Android 기기(SM-F966N)가 ADB에 연결된 상태에서 Debug APK를 재설치하고 앱을 실행했다.
- 앱 프로세스와 `MainActivity`의 전면 표시를 확인했으며, Android `BLUETOOTH_CONNECT`, `CAMERA`, `RECORD_AUDIO` 권한은 허용 상태였다.
- Meta AI 등록/DAT 카메라 권한 및 실제 안경 세션·스트림 상태는 확인 전이므로 위 항목은 미체크로 유지한다.
- ADB 연결이 이후 끊겨 실제 안경 수동 시나리오 및 앱 전용 Logcat 수집은 완료하지 못했다.

## 실제 기기 흐름

- [ ] 음성 명령으로 촬영, 분석, 결과 표시, TTS가 완료된다.
- [ ] 수동 버튼도 동일한 전체 흐름을 실행한다.
- [ ] 실제 이미지의 방향, 색상, 압축 품질과 업로드 크기가 적절하다.
- [ ] 음식, 비음식, 여러 음식 장면의 결과와 오류를 확인했다.
- [ ] 캡처 성공 후 분석 실패가 UI와 logcat에서 별도로 확인된다.

## 복구 및 음성

- [ ] 착용 해제와 접기 후 상태 전이 및 복구가 정상이다.
- [ ] Bluetooth 단절과 세션 `PAUSED/STOPPED` 후 복구가 정상이다.
- [ ] background/foreground 전환과 장시간 스트리밍 후 리소스가 정상이다.
- [ ] 조용한 환경과 생활 소음 환경에서 명령을 검증했다.
- [ ] TTS 중 STT가 중지되고 완료 후 유효한 스트림에서만 재개된다.
- [ ] 자기 음성 재인식과 재시작 지연을 확인하고 조정했다.

## 성능 및 품질 게이트

- [ ] 촬영부터 TTS 완료까지 단계별 지연 시간을 기록했다.
- [x] `.\gradlew.bat test` 통과
- [x] `.\gradlew.bat lint` 통과
- [x] `.\gradlew.bat assembleDebug` 통과
- [ ] 실제 기기 수동 테스트 결과를 기록했다.
