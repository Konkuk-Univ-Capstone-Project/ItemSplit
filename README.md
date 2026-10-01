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
- React 대시보드와 Docker Compose 기반 전체 실행 환경

## 기술 스택

- Backend: Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, PostgreSQL
- Frontend: React 19, TypeScript, Vite, lucide-react
- Test: JUnit 5, Spring Boot Test, Testcontainers, H2
- Runtime: Docker, Docker Compose, Nginx(frontend Docker)

## Docker 실행

백엔드, 프론트엔드, DB를 함께 띄웁니다. **기존 DB 볼륨을 사용한다면 먼저 아래 [기존 DB 변경 적용](#기존-db-변경-적용)을 완료한 뒤 실행합니다.** 빈 DB는 첫 실행 시 Hibernate가 테이블을 생성합니다.

```bash
docker compose up -d --build
```

기본 API 포트는 `8080`, 프론트엔드 포트는 `3000`입니다. 필요하면 `.env`에 `APP_PORT=18080`, `FRONTEND_PORT=13000`처럼 지정할 수 있습니다.

```bash
open http://localhost:3000
```

Docker 종료

```bash
docker compose down
```

Docker 종료 및 볼륨 삭제 — DB와 업로드 이미지가 삭제됩니다. 데이터가 필요 없는 경우에만 실행합니다.

```bash
docker compose down -v
```

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

로컬 Docker 종료

```bash
docker compose down
```

로컬 Docker 종료 및 볼륨 삭제 — DB와 업로드 이미지가 삭제됩니다. 데이터가 필요 없는 경우에만 실행합니다.

```bash
docker compose down -v
```

### 3. 백엔드 실행

기존 DB가 있으면 먼저 [기존 DB 변경 적용](#기존-db-변경-적용)을 수행합니다.

macOS

```bash
./gradlew bootRun
```

Windows

```powershell
gradlew.bat bootRun
```

기본 프로파일은 `local`입니다. Compose는 `.env`를 읽지만, `bootRun`은 셸 환경 변수 또는 기본 DB 접속값을 사용합니다. `.env`의 DB 설정을 변경했다면 같은 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`를 실행 환경에도 설정합니다.

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

## 기존 DB 변경 적용

기존 데이터를 보존하는 기본 절차는 **앱 중지 → 백업 → SQL 적용 → 앱 실행**입니다. 로컬의 `ddl-auto=update`도 이 절차를 대체하지 않습니다. 공통 명세 적용 이전 DB나 Hibernate가 일부 컬럼만 추가한 DB 모두 아래 스크립트를 사용합니다. 성공한 DB에 재실행해도 기존 값은 유지됩니다.

1. IDE/`bootRun`으로 실행 중인 백엔드를 종료합니다. Compose 백엔드도 중지하고 DB만 준비합니다.

```bash
docker compose stop app
docker compose up -d --wait postgres
```

2. DB를 백업합니다. 아래 예시는 macOS/Linux 셸 기준이며, 백업 파일을 Git에 추가하지 않습니다.

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/itemsplit-before-common-contract.dump'
docker compose cp postgres:/tmp/itemsplit-before-common-contract.dump /tmp/itemsplit-before-common-contract.dump
```

백업 파일은 `/tmp`에서 별도 보관 위치로 옮겨 둡니다. 재실행 전에 이전 백업을 보관하여 덮어쓰지 않도록 합니다. Windows에서는 마지막 경로를 원하는 로컬 백업 경로로 바꿉니다.

3. 저장소 루트에서 SQL을 복사하고 실행합니다. 아래 명령은 PowerShell에서도 사용할 수 있습니다.

```bash
docker compose cp db/migrations/001_common_contract.sql postgres:/tmp/001_common_contract.sql
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/001_common_contract.sql'
```

`COMMIT`과 정상 종료를 확인한 뒤 로컬은 `./gradlew bootRun`, Docker는 `docker compose up -d --build`로 시작합니다. 오류가 나면 앱을 시작하지 말고 원인을 해결합니다. SQL은 한 트랜잭션으로 실행되어 중간 실패 시 전체 변경이 취소됩니다.

스크립트는 금액 컬럼을 `BIGINT`로 맞추고, 기존 품목의 제외 여부가 없거나 `NULL`인 경우 `false`로 채운 뒤 기본값과 `NOT NULL`을 적용합니다. 명시적으로 제외된 품목과 기존 요청 식별자는 보존합니다. 기존 금액 오류는 자동 보정하지 않으며 `db/migrations/001_common_contract_audit.sql`로 점검합니다.

빈 DB에는 이 스크립트를 먼저 실행하지 않습니다. 테이블 생성은 Hibernate가 담당합니다. 데이터가 불필요한 개발 DB만 선택적으로 볼륨을 초기화할 수 있으며, 일반 업데이트에는 볼륨 삭제가 필요하지 않습니다.

## 테스트

```bash
./gradlew test
```

PostgreSQL 마이그레이션 회귀 테스트는 Docker가 켜져 있으면 격리된 `postgres:16-alpine` 컨테이너에서 실행됩니다. Docker가 없으면 해당 테스트는 건너뜁니다. 마이그레이션 변경을 검증할 때는 Docker를 켜고 `CommonContractMigrationTest`가 건너뛰어지지 않았는지 확인합니다.

프론트엔드 타입 검사와 빌드는 아래 명령으로 실행합니다.

```bash
cd frontend
npm run build
```

## 환경 설정

### local profile

- 기본 프로파일입니다.
- PostgreSQL에 연결합니다.
- `spring.jpa.hibernate.ddl-auto=update`로 동작합니다. 기존 DB는 먼저 변경 SQL을 적용합니다.
- 스키마 변경 오류가 발생하면 `hibernate.hbm2ddl.halt_on_error=true` 설정으로 앱 시작을 중단합니다.

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
- 명시적으로 제외한 품목만 정산에서 제외됩니다. 제외하지 않은 미배정 품목이나 총액 불일치가 있으면 수정 후 정산할 수 있습니다.

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
