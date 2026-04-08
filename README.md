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
