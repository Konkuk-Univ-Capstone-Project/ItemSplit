# ItemSplit

ItemSplit은 모임/회식 지출을 영수증 단위로 등록하고, 품목별 참여자를 지정해 정산할 수 있는 졸업 프로젝트 웹앱입니다. Spring Boot 백엔드 API와 React + TypeScript + Vite 프론트엔드를 함께 제공합니다.

계정이 있는 사용자뿐 아니라 아직 가입하지 않은 수동 멤버도 방 안의 결제자/참여자로 다룰 수 있도록 `RoomMember`를 정산 기준으로 사용합니다. 초대 토큰으로 참여한 사용자는 기존 수동 멤버와 자신을 매칭하거나 새 멤버로 참여할 수 있고, 정산 결과는 읽기 전용 공유 토큰으로 조회할 수 있습니다.

## 주요 기능

- 이메일/비밀번호 회원가입, 로그인, JWT 기반 인증
- 방 생성/삭제, 방 멤버 조회, 초대 토큰 발급과 참여
- 수동 멤버 추가/이름 수정/삭제, 초대 참여 시 수동 멤버 매칭
- 이미지 영수증 업로드, 수동 영수증 생성/조회/수정/삭제
- 영수증 품목 추가/수정/삭제, 품목별 참여자 지정
- `RoomMember` 기준 결제자/참여자/정산 계산
- 멤버별 부담액/결제액/net과 송금 흐름 조회
- 읽기 전용 공유 토큰을 통한 인증 없는 정산 결과 조회
- React 대시보드와 Docker Compose demo profile 실행 환경

## 기술 스택

- Backend: Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, PostgreSQL
- Frontend: React 19, TypeScript, Vite, lucide-react
- Test: JUnit 5, Spring Boot Test, Testcontainers, H2
- Runtime: Docker, Docker Compose, Nginx(frontend demo)

## 로컬 실행

### 1. 환경 변수 준비

기본값으로도 실행할 수 있지만, 로컬 설정을 명시하려면 예시 파일을 복사해서 `.env`를 만듭니다.

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
docker compose up -d postgres
```

DB 상태 확인

```bash
docker compose ps
```

### 3. 백엔드 실행

macOS

```bash
./gradlew bootRun
```

Windows

```powershell
gradlew.bat bootRun
```

기본 프로파일은 `local`이며, 로컬 DB 접속 정보는 `.env` 또는 기본값을 사용합니다.

### 4. 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

Vite 개발 서버는 기본적으로 `http://localhost:5173`에서 실행되며, `/api` 요청은 `http://localhost:8080` 백엔드로 프록시됩니다.

### 5. 헬스 체크

macOS

```bash
curl http://localhost:8080/actuator/health
```

Windows PowerShell

```powershell
curl.exe http://localhost:8080/actuator/health
```

정상 실행 시 `{"status":"UP"}` 응답을 확인할 수 있습니다.

## 데모 Docker 실행

백엔드, 프론트엔드, DB를 함께 띄우는 데모 환경은 `demo` profile을 사용합니다.

```bash
docker compose --profile demo up -d --build
```

기본 API 포트는 `8080`, 프론트엔드 포트는 `3000`입니다. 필요하면 `.env`에 `APP_PORT=18080`, `FRONTEND_PORT=13000`처럼 지정할 수 있습니다.

```bash
open http://localhost:3000
```

## 테스트

```bash
./gradlew test
```

프론트엔드 타입 검사와 빌드는 아래 명령으로 실행합니다.

```bash
cd frontend
npm run build
```

## 환경 설정

### local profile

- 기본 프로파일입니다.
- PostgreSQL에 연결합니다.
- `spring.jpa.hibernate.ddl-auto=update`로 동작합니다.

### prod profile

운영 환경에서는 `prod` 프로파일을 사용하며 아래 환경 변수를 반드시 주입해야 합니다.

- `SPRING_PROFILES_ACTIVE=prod`
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_SECRET`

운영 프로파일은 `ddl-auto=validate`로 동작합니다.

### 레이트리밋

- 모든 `/api/**` 요청에는 기본 레이트리밋이 적용됩니다.
- 기본값은 `60초 동안 IP당 120회`입니다.
- 조정 환경 변수: `RATE_LIMIT_ENABLED`, `RATE_LIMIT_CAPACITY`, `RATE_LIMIT_WINDOW_SECONDS`
- 제한 초과 시 `429 TOO_MANY_REQUESTS`와 공통 에러 응답 포맷이 반환됩니다.

## API 요약

### Auth

- `POST /api/auth/signup`
- `POST /api/auth/login`
- `GET /api/auth/me`

### Room / Member

- `POST /api/rooms`
- `GET /api/rooms/{roomId}/members`
- `DELETE /api/rooms/{roomId}`
- `POST /api/rooms/{roomId}/members/manual`
- `PUT /api/rooms/{roomId}/members/{memberId}`
- `DELETE /api/rooms/{roomId}/members/{memberId}`
- `POST /api/rooms/{roomId}/invite-token`
- `GET /api/rooms/join-options?token={inviteToken}`
- `POST /api/rooms/join?token={inviteToken}`

### Receipt / Item

- `GET /api/rooms/{roomId}/receipts`
- `GET /api/rooms/{roomId}/receipts/{receiptId}`
- `POST /api/rooms/{roomId}/receipts/image`
- `POST /api/rooms/{roomId}/receipts/manual`
- `PUT /api/rooms/{roomId}/receipts/{receiptId}`
- `DELETE /api/rooms/{roomId}/receipts/{receiptId}`
- `POST /api/rooms/{roomId}/receipts/{receiptId}/items`
- `PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}`
- `DELETE /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}`

### Assignment / Settlement / Share

- `GET /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees`
- `PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees`
- `GET /api/rooms/{roomId}/settlements`
- `POST /api/rooms/{roomId}/share-token`
- `GET /api/shared/rooms/{token}/settlements`

## 정산 규칙

- 품목 금액은 해당 품목에 배정된 멤버 수로 나누어 부담액을 계산합니다.
- 1원 단위 나머지는 `roomId`와 `itemId` 기반의 고정 seed로 참여자에게 분배해 조회마다 같은 결과를 보장합니다.
- 결제액은 영수증의 결제자로 지정된 멤버에게 합산됩니다.
- `net = paid - burden`이며, 양수면 받을 금액, 음수면 보낼 금액입니다.
- 참여자가 지정되지 않은 품목은 정산에서 제외됩니다.

## 프로젝트 구조

```text
src/main/java/com/capstone/itemsplit
├── auth          # 회원가입, 로그인, JWT 인증
├── room          # 방, 멤버, 초대/공유 토큰
├── receipt       # 영수증 생성/조회/수정/삭제
├── item          # 영수증 품목 관리
├── assignment    # 품목별 참여자 지정
├── settlement    # 정산 계산
├── storage       # 이미지 파일 저장
├── common        # 공통 응답, 예외, 레이트리밋
└── config        # 보안 및 애플리케이션 설정

frontend
└── src           # React + TypeScript 웹 클라이언트
```
