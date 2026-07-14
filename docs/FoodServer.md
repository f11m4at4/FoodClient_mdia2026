# Food Analysis Server

## 목적

이 문서는 `CameraAccess` 앱이 사용하는 로컬 음식 분석 서버의 구현 기준을 정의한다. 서버는 Python FastAPI 기반으로 동작하며, 앱 대신 OpenAI API를 호출해 음식명과 500자 이내 조리법을 생성한다.

- 서버 런타임: Python 3.11+
- 웹 프레임워크: FastAPI
- ASGI 서버: Uvicorn
- OpenAI 모델: `gpt-4o-mini`
- 개발 바인딩: `0.0.0.0:8080`

앱은 OpenAI API 키, OpenAI 엔드포인트, 프롬프트, 모델 선택 로직을 포함하지 않는다. 이 책임은 모두 서버에 있다.

## 앱 연동 계약

앱은 debug 빌드에서만 `local.properties`의 `analysis_server_url` 값을 `BuildConfig.ANALYSIS_SERVER_URL`로 주입받는다.

```properties
analysis_server_url=http://<development-pc-ip>:8080/
```

- 앱 호출 경로: `POST /v1/food-analysis`
- 요청 형식: `multipart/form-data`
- 파일 필드명: `photo`
- 파일 형식: `image/jpeg`
- release 앱은 `ANALYSIS_SERVER_URL`이 비어 있고 cleartext HTTP가 차단된다.

앱 클라이언트는 다음 규칙에 맞춰 동작한다.

- `photo`라는 이름의 JPEG 파일을 `capture.jpg` 파일명으로 업로드한다.
- 성공 응답은 JSON의 `foodName`, `recipeText`, `requestId`를 읽는다.
- `foodName`이 비어 있으면 음식 미검출로 처리한다.
- `recipeText`가 500자를 초과해도 앱이 500자로 잘라 표시하지만, 서버가 먼저 500자 이내를 보장해야 한다.
- 실패 응답은 본문을 파싱하지 않고 HTTP 상태 코드만 사용한다.

## 권장 프로젝트 구조

```text
food-analysis-server/
├── app/
│   ├── main.py
│   ├── api.py
│   ├── settings.py
│   ├── schemas.py
│   ├── openai_client.py
│   └── services/
│       └── food_analysis.py
├── requirements.txt
└── .env
```

## FastAPI 엔드포인트

### 1. 분석 엔드포인트

```http
POST /v1/food-analysis
Content-Type: multipart/form-data
```

#### 요청 제약

- `photo` 필드는 필수다.
- `Content-Type`은 `image/jpeg`만 허용한다.
- 파일 크기는 서버에서 제한한다. 권장 상한은 10MB다.
- 빈 파일, JPEG가 아닌 파일, 손상된 바이트는 4xx로 거절한다.

#### 성공 응답

```json
{
  "foodName": "김치볶음밥",
  "recipeText": "팬에 식용유를 두르고 대파와 김치를 볶은 뒤 밥을 넣어 고루 섞습니다. 고추장이나 간장으로 간을 맞추고, 햄이나 참치를 넣어도 됩니다. 마지막에 참기름을 약간 넣고 계란 프라이를 올려 마무리합니다.",
  "requestId": "req_20260714_01HXYZ123ABC"
}
```

#### 실패 응답 예시

앱은 실패 본문을 사용하지 않지만, 디버깅과 운영 관측성을 위해 JSON 에러 포맷을 유지하는 편이 좋다.

```json
{
  "error": {
    "code": "unsupported_media_type",
    "message": "photo must be image/jpeg"
  },
  "requestId": "req_20260714_01HXYZ123ABC"
}
```

### 2. 진단 엔드포인트

체크리스트의 네트워크 확인을 위해 간단한 진단 엔드포인트를 두는 것을 권장한다.

```http
GET /healthz
```

```json
{
  "status": "ok"
}
```

테스트 폰 브라우저에서 `http://<development-pc-ip>:8080/healthz`에 접근 가능해야 한다.

## OpenAI 호출 기준

서버는 업로드된 JPEG를 OpenAI API로 전달하고 `gpt-4o-mini`를 사용해 음식명을 식별한 뒤 짧은 조리법을 생성한다. `gpt-4o-mini`는 OpenAI 공식 문서 기준 텍스트와 이미지 입력을 받을 수 있다.

권장 프롬프트 규칙:

- 음식이 보이면 대표 음식명 하나를 반환한다.
- 음식이 불분명하면 빈 `foodName`을 반환하게 한다.
- `recipeText`는 한국어 기준 500자 이내로 제한한다.
- 장황한 설명, 면책 문구, 마크다운, 코드블록은 금지한다.
- 결과는 반드시 JSON 스키마에 맞추게 한다.

권장 출력 스키마:

```json
{
  "foodName": "string",
  "recipeText": "string"
}
```

서버 내부 처리 순서:

1. 업로드 파일 존재 여부, MIME, 크기를 검증한다.
2. 요청별 `requestId`를 생성한다.
3. JPEG 바이트를 Base64 또는 OpenAI SDK가 요구하는 이미지 입력 형식으로 변환한다.
4. OpenAI API에 `gpt-4o-mini`로 요청한다.
5. 응답 JSON을 검증한다.
6. `foodName` trim, `recipeText` trim, 500자 제한을 적용한다.
7. 앱 계약에 맞는 JSON을 반환한다.

## FastAPI 구현 예시

아래 예시는 문서화용 골격이다.

```python
from uuid import uuid4

from fastapi import FastAPI, File, HTTPException, UploadFile
from openai import OpenAI

app = FastAPI()
client = OpenAI()

MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_RECIPE_LENGTH = 500


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/food-analysis")
async def analyze_food(photo: UploadFile = File(...)) -> dict[str, str]:
    request_id = f"req_{uuid4().hex}"

    if photo.content_type != "image/jpeg":
        raise HTTPException(status_code=415, detail="photo must be image/jpeg")

    image_bytes = await photo.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="photo is empty")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="photo is too large")

    # OpenAI 호출부는 JSON 강제와 500자 제한을 함께 적용한다.
    result = {
        "foodName": "김치볶음밥",
        "recipeText": "500자 이내 조리법",
    }

    food_name = result["foodName"].strip()
    recipe_text = result["recipeText"].strip()[:MAX_RECIPE_LENGTH]

    return {
        "foodName": food_name,
        "recipeText": recipe_text,
        "requestId": request_id,
    }
```

## 요청/응답 예시

### curl 요청 예시

```bash
curl -X POST "http://127.0.0.1:8080/v1/food-analysis" \
  -H "Accept: application/json" \
  -F "photo=@capture.jpg;type=image/jpeg"
```

### 성공 응답 예시

```json
{
  "foodName": "된장찌개",
  "recipeText": "냄비에 멸치육수나 물을 붓고 된장을 풀어 끓입니다. 애호박, 양파, 두부, 버섯, 감자를 넣고 한소끔 더 끓입니다. 다진 마늘과 고춧가루를 약간 넣어 간을 맞추고, 마지막에 대파와 청양고추를 넣어 마무리합니다.",
  "requestId": "req_20260714_01HXYZ123ABC"
}
```

### 실패 응답 예시

```json
{
  "error": {
    "code": "food_not_detected",
    "message": "No clear food item was detected in the image."
  },
  "requestId": "req_20260714_01HXYZ123ABC"
}
```

권장 상태 코드:

- `200 OK`: 정상 분석
- `200 OK` + 빈 `foodName`: 음식 미검출. 현재 앱은 이 경우를 `FoodNotDetected`로 처리한다.
- `400 Bad Request`: 필수 필드 누락, 빈 파일, 잘못된 입력
- `413 Payload Too Large`: 크기 초과
- `415 Unsupported Media Type`: JPEG 아님
- `502 Bad Gateway`: OpenAI 응답 이상
- `504 Gateway Timeout`: OpenAI 또는 업스트림 타임아웃
- `500 Internal Server Error`: 기타 예외

앱은 `200` 이외의 상태를 `HttpStatus(code)`로 처리한다. 따라서 음식이 보이지 않는 경우는 `422`보다 `200` 응답에서 빈 `foodName`을 반환하는 편이 현재 앱 계약과 맞다.

## 로깅과 보안

`docs/PLAN.md`와 체크리스트에 맞춰 다음을 지킨다.

- 앱 소스, APK, BuildConfig, logcat에 OpenAI API 키를 넣지 않는다.
- 서버의 OpenAI API 키는 `.env` 또는 OS 환경 변수로만 주입한다.
- 사진 바이트, 조리법 전문, 인증정보는 로그에 남기지 않는다.
- 로그에는 `requestId`, HTTP 상태, 처리 시간, 실패 분류만 남긴다.
- 로컬 개발 외 환경에서는 HTTPS를 사용한다.
- release 앱은 cleartext HTTP를 허용하지 않으므로 로컬 서버는 debug 통합 전용이다.

권장 환경 변수:

```env
OPENAI_API_KEY=<server-only-secret>
HOST=0.0.0.0
PORT=8080
```

## 체크리스트 반영 항목

이 문서 기준 구현이 충족해야 할 항목:

- 개발 PC와 테스트 폰이 같은 네트워크에 연결된다.
- 서버가 `0.0.0.0`에 바인딩되고 필요한 포트만 방화벽에서 허용된다.
- 테스트 폰에서 `/healthz` 진단 엔드포인트 접근이 가능하다.
- `photo` JPEG multipart 요청과 `foodName`, `recipeText`, `requestId` 응답 계약이 일치한다.
- 서버가 500자 이내 조리법을 반환한다.
- timeout, 서버 중단, 4xx/5xx, 잘못된 JSON, 음식 미검출에 대한 상태 코드를 재현할 수 있다.
- 로그에 캡처 이미지 바이트, 조리법 전문, OpenAI API 키가 남지 않는다.

## 예상 실행 결과

서버가 정상 실행되면 다음 흐름이 성립한다.

1. 테스트 폰에서 `/healthz`에 접근하면 `{"status":"ok"}`가 반환된다.
2. 앱에서 사진 촬영 후 `Uploading` 상태가 표시된다.
3. 서버가 JPEG를 검증하고 OpenAI에 분석을 요청한다.
4. 서버는 음식명, 500자 이내 조리법, `requestId`를 JSON으로 반환한다.
5. 앱은 결과를 화면에 표시하고 TTS로 읽는다.
6. 오류가 발생하면 앱은 HTTP 상태 기준으로 분석 실패를 구분한다.

## 사용자 테스트 절차

1. 개발 PC의 서버 환경 변수에 `OPENAI_API_KEY`를 설정한다.
2. 서버를 `uvicorn app.main:app --host 0.0.0.0 --port 8080`으로 실행한다.
3. 테스트 폰과 개발 PC를 같은 Wi-Fi에 연결한다.
4. 테스트 폰 브라우저에서 `http://<development-pc-ip>:8080/healthz` 호출이 성공하는지 확인한다.
5. 저장소 루트 `local.properties`에 `analysis_server_url=http://<development-pc-ip>:8080/`를 설정한다.
6. debug 앱을 설치하고 사진 촬영을 수행한다.
7. 정상 케이스에서 음식명, 조리법, TTS 출력이 보이는지 확인한다.
8. JPEG가 아닌 파일, 빈 파일, 서버 중단, 지연, OpenAI 실패를 순서대로 재현해 상태 코드와 앱 UI를 확인한다.
9. logcat과 서버 로그에서 `requestId`, 상태 코드, 처리 시간만 남는지 확인한다.
10. release 빌드에서 cleartext HTTP와 서버 URL 주입이 차단되는지 확인한다.

## 참고

- OpenAI 모델 문서: https://developers.openai.com/api/docs/models/gpt-4o-mini
- OpenAI 모델 개요: https://developers.openai.com/api/docs/models
