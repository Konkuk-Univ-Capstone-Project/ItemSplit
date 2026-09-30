# Step 09 - Shared Read-only Link, Rate Limit, Demo Packaging

## 목표

- OCR 기능은 보류하고, B 담당 남은 작업 중 공유 토큰/권한, 운영 기본 요소, 데모 배포 기반을 먼저 진행한다.
- 공유 링크는 읽기 전용 정산 조회만 허용한다.
- API 공통 운영 방어선으로 레이트리밋과 추가 예외 표준 응답을 제공한다.
- 데모 실행을 위한 Docker 패키징 문서를 보강한다.

## 이번 단계에서 추가한 내용

### 1. 공유 토큰 엔티티/저장소

- `RoomShareToken` 엔티티를 추가했다.
- 방마다 현재 유효한 공유 토큰 1개를 관리한다.
- 토큰은 `InviteTokenGenerator`를 재사용해 URL-safe 랜덤 문자열로 만든다.
- 토큰 만료 시각을 저장하고, 기본 만료 기간은 14일이다.
- 재발급 시 같은 방의 기존 토큰 문자열을 교체하므로 이전 공유 링크는 무효화된다.

### 2. 공유 토큰 발급 API

- `POST /api/rooms/{roomId}/share-token`을 추가했다.
- 요청자는 JWT 인증을 통과해야 하며, 해당 room의 멤버여야 한다.
- 응답에는 `roomId`, `roomName`, `token`, `expiresAt`, `readOnly=true`가 포함된다.
- 비멤버 요청은 기존 `RoomAuthorizationService` 흐름대로 `403 FORBIDDEN`을 반환한다.

### 3. 읽기 전용 공유 정산 조회

- `GET /api/shared/rooms/{token}/settlements`를 추가했다.
- Spring Security에서 `GET /api/shared/**`만 공개 경로로 허용했다.
- 공유 토큰으로는 쓰기 API를 호출할 수 없고, 정산 결과 조회만 가능하다.
- 만료되었거나 재발급으로 교체된 토큰은 `VALIDATION_ERROR`로 거부한다.
- 기존 `SettlementService` 계산 로직을 재사용하도록 `calculateShared(Room room)` 경로를 추가했다.

### 4. 레이트리밋

- `RateLimitFilter`와 `RateLimitProperties`를 추가했다.
- 기본 정책은 `/api/**` 요청에 대해 `60초 동안 IP당 120회`이다.
- 조정 환경 변수는 아래와 같다.
  - `RATE_LIMIT_ENABLED`
  - `RATE_LIMIT_CAPACITY`
  - `RATE_LIMIT_WINDOW_SECONDS`
- 제한 초과 시 `429 TOO_MANY_REQUESTS`와 공통 실패 응답을 반환한다.
- 테스트 프로파일에서는 기본 비활성화하고, 전용 테스트에서만 활성화했다.

### 5. 예외 응답 표준 보강

- `TOO_MANY_REQUESTS` 에러 코드를 추가했다.
- 잘못된 JSON, 누락된 query parameter, 누락된 multipart part, 경로/요청 값 타입 불일치를 `VALIDATION_ERROR` 응답으로 통일했다.
- 기존 `ApiResponse` / `ErrorResponse` 포맷은 그대로 유지했다.

### 6. 데모 패키징

- `Dockerfile`을 추가했다.
  - Gradle bootJar 빌드 단계와 JRE 실행 단계를 분리했다.
  - `/app/storage`를 로컬 파일 저장 경로로 사용한다.
- `.dockerignore`를 추가했다.
- `docker-compose.yml`에 `demo` profile의 `app` 서비스를 추가했다.
  - 기존 `docker compose up -d postgres` 방식은 유지된다.
  - API까지 실행하려면 `docker compose --profile demo up -d --build`를 사용한다.
- `.env.example`과 README에 데모/운영 환경 변수를 추가했다.

## 테스트

- `RoomShareControllerTest`
  - 방 멤버의 공유 토큰 발급
  - 공유 토큰으로 인증 없이 정산 조회
  - 공유 토큰 재발급 시 이전 토큰 무효화
  - 비멤버 공유 토큰 발급 차단
  - 만료 공유 토큰 조회 차단
- `RateLimitFilterTest`
  - 제한량 초과 시 `429 TOO_MANY_REQUESTS` 응답
- `PingControllerTest`
  - malformed JSON 요청의 `VALIDATION_ERROR` 표준 응답

## 검증 결과

- `./gradlew test` 성공
- `docker compose config` 성공
- `docker compose --profile demo config` 성공

## 메모

- OCR 자체와 OCR 결과 수정 플로우는 이번 단계에서 건드리지 않았다.
- 공유 조회는 현재 정산 결과에만 열어 두었다. 추후 프론트 요구에 따라 receipt/item 읽기 전용 조회를 공유 토큰에 붙일 수 있다.
