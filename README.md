# ItemSplit

졸업 프로젝트 백엔드입니다.

## 로컬 실행

### 1. 환경 변수 준비

기본값으로도 실행할 수 있지만, 팀 공통 설정을 맞추려면 예시 파일을 복사해서 `.env`를 만듭니다.

macOS

```bash
cp .env.example .env
```

Windows PowerShell

```powershell
Copy-Item .env.example .env
```

### 2. DB 실행

```bash
docker compose up -d
```

DB 상태 확인

```bash
docker compose ps
```

### 3. 서버 실행

macOS

```bash
./gradlew bootRun
```

Windows

```powershell
gradlew.bat bootRun
```

기본 프로파일은 `local`이며, 로컬 DB 접속 정보는 `.env` 또는 기본값을 사용합니다.

### 4. 헬스 체크

macOS

```bash
curl http://localhost:8080/actuator/health
```

Windows PowerShell

```powershell
curl.exe http://localhost:8080/actuator/health
```

정상 실행 시 `{"status":"UP"}` 응답을 확인할 수 있습니다.

## 운영 프로파일

운영 환경에서는 `prod` 프로파일을 사용하며 아래 환경 변수를 반드시 주입해야 합니다.

- `SPRING_PROFILES_ACTIVE=prod`
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_SECRET`

운영 프로파일은 `ddl-auto=validate`로 동작합니다.

## 데모 Docker 실행

DB만 실행할 때는 기존처럼 아래 명령을 사용합니다.

```bash
docker compose up -d postgres
```

API까지 함께 띄우는 데모 환경은 `demo` profile을 사용합니다.

```bash
docker compose --profile demo up -d --build
```

기본 API 포트는 `8080`입니다. 필요하면 `.env`에 `APP_PORT=18080`처럼 지정할 수 있습니다.

## 운영 기본값

- 모든 `/api/**` 요청에는 기본 레이트리밋이 적용됩니다.
- 기본값은 `60초 동안 IP당 120회`입니다.
- 조정 환경 변수: `RATE_LIMIT_ENABLED`, `RATE_LIMIT_CAPACITY`, `RATE_LIMIT_WINDOW_SECONDS`
- 제한 초과 시 `429 TOO_MANY_REQUESTS`와 공통 에러 응답 포맷이 반환됩니다.

## Assignment API

Sprint 3 기준으로 item assignees 조회와 전체 교체 API가 준비되어 있습니다.

### 1. 참여자 목록 조회

```http
GET /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees
Authorization: Bearer {accessToken}
```

### 2. 참여자 목록 전체 교체

```http
PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "memberIds": [2, 3]
}
```

### 3. 정책

- `memberIds`는 현재 모델 기준 `RoomMember.id`가 아니라 `User.id`입니다.
- 요청자는 반드시 해당 room의 멤버여야 합니다.
- `memberIds`에 들어가는 사용자도 모두 해당 room의 멤버여야 합니다.
- `memberIds: []`는 해당 item의 assignee 전체 해제를 의미합니다.
- 중복 `memberIds`는 서버에서 정리한 뒤 저장합니다.

### 4. 성공 응답 예시

```json
{
  "success": true,
  "data": {
    "roomId": 1,
    "receiptId": 10,
    "itemId": 100,
    "itemName": "Pasta",
    "assignees": [
      {
        "userId": 2,
        "email": "alice@example.com",
        "nickname": "Alice"
      },
      {
        "userId": 3,
        "email": "bob@example.com",
        "nickname": "Bob"
      }
    ]
  },
  "error": null
}
```

### 5. A 연동 메모

- 정산 계산 시 item별 assignee 목록은 `GET /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees`로 조회할 수 있습니다.
- 서버는 `receipt -> room`, `item -> receipt`, `assignee user -> room member` 관계를 모두 검증하므로, 성공 응답에 포함된 assignee는 항상 해당 room 멤버라는 전제를 두고 정산 엔진에서 사용할 수 있습니다.
- 빈 assignee 목록은 아직 아무도 지정되지 않았거나, 전체 해제된 상태로 해석하면 됩니다.

## Receipt API

`Receipt`는 이제 이미지 업로드 전용 엔티티가 아니라, room 안의 영수증/지출 묶음을 표현하는 공통 루트입니다.

### 1. 이미지 영수증 업로드

```http
POST /api/rooms/{roomId}/receipts/image
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
```

- `sourceType`은 `IMAGE_UPLOAD`
- 파일 메타데이터(`storedPath`, `originalFilename`, `contentType`, `fileSize`)가 함께 저장됩니다.

### 2. 수동 입력 영수증 생성

```http
POST /api/rooms/{roomId}/receipts/manual
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "name": "Dinner Manual Entry",
  "items": [
    { "name": "Pasta", "price": 15000, "quantity": 1 },
    { "name": "Pizza", "price": 22000, "quantity": 2 }
  ]
}
```

- `sourceType`은 `MANUAL`
- 수동 입력 영수증은 첨부 파일이 없으므로 파일 메타데이터는 비어 있습니다.
- item 목록은 receipt 생성과 함께 저장됩니다.

### 3. sourceType 정책

- `IMAGE_UPLOAD`: 이미지 파일을 올려 만든 receipt
- `MANUAL`: 텍스트로 receipt와 item 목록을 직접 입력해 만든 receipt

정산, assignment, room 권한 로직은 두 타입 모두 동일한 `Receipt -> Item -> Room` 구조를 기준으로 동작합니다.

## Shared Read-only API

방 멤버는 정산 결과 공유용 읽기 전용 토큰을 발급할 수 있습니다.

### 1. 공유 토큰 발급/재발급

```http
POST /api/rooms/{roomId}/share-token
Authorization: Bearer {accessToken}
```

- 요청자는 해당 room의 멤버여야 합니다.
- 호출할 때마다 새 토큰으로 교체되며, 이전 공유 토큰은 더 이상 사용할 수 없습니다.
- 기본 만료 기간은 14일입니다.

### 2. 공유 정산 조회

```http
GET /api/shared/rooms/{token}/settlements
```

- 인증 없이 조회할 수 있습니다.
- 쓰기 API는 공유 토큰으로 호출할 수 없습니다.
- 응답에는 `readOnly=true`, `shareExpiresAt`, 정산 멤버 목록이 포함됩니다.
