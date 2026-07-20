# Food 분석 서버 제작 프롬프트

아래 프롬프트를 사용해 FoodClient Android 앱과 통신하는 로컬 분석 서버를 제작한다.

## 구현 프롬프트

```text
Python 3.11+과 FastAPI로 FoodClient용 음식 분석 서버를 구현해 줘. 결과물은 독립 실행
가능한 서버 프로젝트여야 하며, OpenAI Python SDK와 `gpt-4o-mini` 모델을 사용한다.

### 보안과 환경 변수
- OpenAI API 키는 서버의 `OPENAI_API_KEY` 환경 변수에서만 읽는다. 키를 앱, 응답, 로그,
  소스 저장소, `local.properties`에 넣지 않는다.
- Android 앱의 서버 주소는 앱 프로젝트 루트의 `local.properties`에
  `ANALYZE_SERVER_URL=http://<PC-LAN-IP>:8080` 형식으로 보관하도록 안내한다.
- 현재 FoodClient 빌드 스크립트는 하위 호환 키인 `analysis_server_url`을 읽어
  `BuildConfig.ANALYSIS_SERVER_URL`로 노출한다. 실제 연결까지 완료하려면 빌드 스크립트가
  `ANALYZE_SERVER_URL`도 읽도록 수정하거나, 전환 기간에는 같은 주소를
  `analysis_server_url`에도 설정한다. 어떤 경우에도 이 파일에 OpenAI API 키를 넣지 않는다.

### HTTP 계약 (변경 금지)
- `POST /v1/food-analysis`
- 요청은 JSON이 아니라 `multipart/form-data`이다. Android 클라이언트가 다음 한 필드를
  전송한다.
  - 필드명: `photo`
  - 파일명: `capture.jpg`
  - Content-Type: `image/jpeg`
- JPEG가 없거나 비어 있거나 허용 크기를 초과하면 400 또는 413 JSON 오류를 반환한다.
- 성공 시 HTTP 200과 아래 JSON만 반환한다. `recipeText`는 공백을 제외하고 비어 있으면 안
  되며 최대 500자다. `requestId`는 서버가 매 요청마다 생성하는 추적 ID다.

```json
{
  "foodName": "김치볶음밥",
  "recipeText": "김치와 밥을 볶고 간장으로 간을 맞춘 뒤 달걀프라이를 올립니다.",
  "requestId": "req_01JABCDEF123456789"
}
```

- 음식이 아니거나 신뢰할 수 있게 식별할 수 없으면 HTTP 200으로 아래처럼 `foodName`을 빈
  문자열로 반환한다. Android 앱은 이를 음식 미검출로 처리한다.

```json
{
  "foodName": "",
  "recipeText": "",
  "requestId": "req_01JABCDEF123456790"
}
```

- 서버 오류는 세부 오류나 API 키를 노출하지 않는 JSON으로 반환한다.

```json
{
  "detail": "Food analysis is temporarily unavailable.",
  "requestId": "req_01JABCDEF123456791"
}
```

### 요청 데이터 예시
아래 JSON은 multipart 요청의 내용을 설명하기 위한 표현이며, 실제 HTTP 본문은 이 JSON이
아니라 `photo` 파일 파트 하나다.

```json
{
  "photo": {
    "filename": "capture.jpg",
    "contentType": "image/jpeg",
    "binary": "<JPEG bytes>"
  }
}
```

### OpenAI 분석
- 수신한 JPEG를 base64 data URL 또는 OpenAI SDK가 지원하는 이미지 입력으로 전달해
  `gpt-4o-mini`가 음식 여부, 음식명, 500자 이내의 한국어 조리법을 판단하게 한다.
- 모델 출력은 JSON 객체로 제한하고 `foodName`, `recipeText` 두 필드를 검증한다. 모델이
  비정상 JSON을 반환하거나 필수 필드가 없으면 502로 처리한다.
- 음식 미검출의 경우에는 위 계약대로 두 문자열을 빈 문자열로 정규화한다.
- 서버가 `requestId`를 생성하고, 응답의 조리법을 500자로 자른 뒤 반환한다.
- 사진 원본, base64 문자열, 모델 전문 응답, `OPENAI_API_KEY`를 로그에 남기지 않는다.
  로그에는 requestId, HTTP 상태, 처리 시간, 오류 종류만 남긴다.

### 운영과 검증
- `/healthz`에서 `{ "status": "ok" }`를 반환한다.
- 개발 기기에서 접근 가능하도록 `uvicorn`을 `0.0.0.0`, 포트 `8080`에 바인딩한다.
- `requirements.txt`, `.env.example`(키 값 없이 변수명만), `README.md`, FastAPI 앱 코드,
  pytest 테스트를 포함한다. `.env`는 `.gitignore`에 넣는다.
- 테스트는 정상 JPEG 요청, photo 누락, 잘못된 MIME 타입, 크기 초과, 음식 미검출, OpenAI
  실패, 응답 500자 제한을 포함한다. OpenAI 호출은 테스트에서 mock 처리한다.
- 실행 명령과 curl multipart 예시를 README에 제공한다.
```

## FoodClient 실제 통신 요약

| 항목 | 현재 앱 구현 |
| --- | --- |
| 엔드포인트 | `POST {baseUrl}/v1/food-analysis` |
| 요청 본문 | `multipart/form-data`, JPEG `photo` 파트 |
| 연결/읽기/전체 제한 | 10초 / 45초 / 60초 |
| 성공 응답 | `foodName`, `recipeText`, 선택적 `requestId` |
| 앱 검증 | 음식명과 조리법은 빈 값이면 실패, 조리법은 500자로 제한 |
| 비정상 응답 | 4xx/5xx는 HTTP 오류, 잘못된 JSON은 응답 오류, 빈 음식명은 음식 미검출 |

## 로컬 실행 및 연결 예시

```powershell
$env:OPENAI_API_KEY = "<server-only key>"
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

```properties
# local.properties - OpenAI 키는 절대 넣지 않는다.
ANALYZE_SERVER_URL=http://192.168.0.10:8080
# 현재 Android 빌드와 연결하려면 아래 호환 키도 같은 값으로 둔다.
analysis_server_url=http://192.168.0.10:8080
```

```powershell
curl.exe -X POST "http://127.0.0.1:8080/v1/food-analysis" `
  -F "photo=@.\sample-food.jpg;type=image/jpeg"
```

## 수락 기준

- Android 앱이 JPEG `photo` multipart 요청으로 200 응답을 받고 음식명과 500자 이하 조리법을
  표시할 수 있다.
- OpenAI API 키는 서버 환경 변수에만 존재하며, 앱 소스·APK·로그·응답에 포함되지 않는다.
- 실제 휴대폰에서 PC의 LAN 주소와 방화벽 허용 포트를 통해 `/healthz` 및 분석 API에 접근할 수
  있다.
