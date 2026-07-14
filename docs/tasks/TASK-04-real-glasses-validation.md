# TASK-04 실제 Meta Ray-Ban 검증

## 목적

MockDeviceKit에서 검증한 흐름을 실제 Meta Ray-Ban 기기로 확장하고 기기 상태, 이미지 특성, 음성 환경, 네트워크 지연에 따른 복구 동작을 확인한다.

## 선행 조건

- TASK-03에서 로컬 분석 서버 통합이 완료되어야 한다.
- Meta AI 앱, 지원 기기, Developer Mode 및 DAT 등록 환경이 준비되어야 한다.

## 구현 범위

1. 기기 연결 및 권한
   - Developer Mode, 앱 등록, Bluetooth 및 DAT 카메라 권한을 확인한다.
   - 등록 후 세션 `STARTED`, 스트림 `STREAMING` 도달을 logcat과 UI로 확인한다.

2. 실제 이미지 처리
   - 안경 촬영 결과의 형식, 방향, 색상, 압축 품질과 업로드 크기를 확인한다.
   - 음식, 비음식, 여러 음식 장면에서 분석 결과와 오류 메시지를 검증한다.
   - 캡처 성공 직후 분석 요청이 실패하는 경우에도 촬영과 분석 로그가 분리되는지 확인한다.

3. 생명주기 및 복구
   - 착용 해제, 접기, Bluetooth 단절, 세션 `PAUSED/STOPPED`, 스트림 오류를 재현한다.
   - 화면 background/foreground 전환 및 장시간 스트리밍 뒤 STT/TTS/분석 작업 상태를 확인한다.
   - 종료된 세션은 재사용하지 않고 새 세션을 생성해 복구한다.

4. 음성 및 성능 조정
   - 조용한 환경과 생활 소음 환경에서 명령 인식률을 확인한다.
   - TTS 출력의 자기 음성 재인식이 발생하지 않도록 중지/재개 시점을 조정한다.
   - 촬영, 업로드, 결과 표시, TTS 완료까지 단계별 지연 시간을 측정한다.

## 완료 기준

- 실제 기기에서 음성 및 수동 촬영 전체 흐름이 반복 동작한다.
- 기기/네트워크 오류 후 사용자 메시지가 표시되고 정상 상태로 복구할 수 있다.
- TTS 중 STT가 중지되고 완료 후 유효한 스트림에서만 자동 재개된다.
- 자동화 결과와 별도로 실제 기기 수동 테스트 기록이 남아 있다.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

실제 기기 검증은 `docs/checklist/TASK-04-real-glasses-validation-checklist.md`에 결과를 기록한다.

