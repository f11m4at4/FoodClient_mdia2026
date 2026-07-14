# 로컬 분석 서버 설정

개발 PC와 Android 기기를 같은 네트워크에 연결한 뒤, 저장소 루트의 Git 제외 파일인 `local.properties`에 다음 항목을 추가한다.

```properties
analysis_server_url=http://<development-pc-ip>:8080/
```

- 서버는 `POST /v1/food-analysis`를 제공해야 한다.
- 이 값은 debug `BuildConfig.ANALYSIS_SERVER_URL`에만 주입된다.
- 값이 없으면 debug 앱은 빈 URL로 안전하게 빌드되고, 촬영 성공 후 서버 설정 안내를 표시한다.
- release 빌드에는 빈 URL이 주입되며 cleartext HTTP가 금지된다.
- 사설 IP, 인증정보, OpenAI API 키는 소스나 Gradle 파일에 추가하지 않는다.
