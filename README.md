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

백엔드, 프론트엔드, DB를 함께 띄웁니다. DB 스키마는 앱이 시작할 때 Flyway가 만들고 갱신합니다. 기존 DB 볼륨을 쓰고 있다면 [DB 스키마 변경](#db-스키마-변경)의 기존 DB 항목을 먼저 확인합니다.

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

스키마는 시작할 때 Flyway가 적용합니다. 기존 DB가 있으면 [DB 스키마 변경](#db-스키마-변경)의 기존 DB 항목을 확인합니다.

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

## DB 스키마 변경

스키마는 Flyway가 관리합니다. 앱이 시작할 때 `src/main/resources/db/migration`의 스크립트를 버전 순서로 적용하고, 그 뒤 Hibernate가 엔티티와 스키마가 맞는지 검사합니다(`ddl-auto=validate`). 맞지 않으면 앱이 시작하지 않습니다. Hibernate는 테이블이나 컬럼을 자동으로 만들지 않습니다.

| 스크립트 | 내용 |
|---|---|
| `V1__baseline.sql` | Flyway 도입 전의 스키마. 빈 DB에서만 실행 |
| `V2__common_contract.sql` | 공통 명세 변경(금액 `BIGINT`, 정산 제외, 요청 식별자, 금액 제약) |

### 스키마를 바꿀 때

1. 엔티티를 수정합니다.
2. 다음 번호로 `V{번호}__{설명}.sql`을 추가합니다.
3. 이미 `main`에 들어간 스크립트는 고치지 않습니다. 내용이 달라지면 Flyway가 앱 시작을 막습니다. 고칠 내용은 새 스크립트로 추가합니다.

### 기존 DB

Flyway 도입 전에 만든 DB(기존 Docker 볼륨 포함)는 별도 작업 없이 앱을 시작하면 됩니다. 첫 시작 때 테이블이 이미 있는 DB를 버전 1로 표시하고 `V2`부터 적용합니다. `V2`는 공통 명세 SQL을 수동으로 적용한 DB, 적용하지 않은 DB, Hibernate가 일부 컬럼만 추가한 DB 모두에서 기존 값을 보존합니다. 스크립트 하나는 한 트랜잭션으로 실행되어, 실패하면 변경이 취소되고 앱이 시작하지 않습니다.

보존할 데이터가 있으면 시작 전에 백업합니다. 아래 예시는 macOS/Linux 셸 기준이며, 백업 파일을 Git에 추가하지 않습니다.

```bash
docker compose up -d --wait postgres
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/itemsplit-backup.dump'
docker compose cp postgres:/tmp/itemsplit-backup.dump /tmp/itemsplit-backup.dump
```

시작이 스키마 검증에서 실패하면 그 DB는 `V1`보다 오래된 구조입니다. 데이터가 필요 없는 개발 DB는 `docker compose down -v`로 볼륨을 지우고 다시 시작합니다.

기존 금액 오류는 자동 보정하지 않으며 `db/migrations/001_common_contract_audit.sql`로 점검합니다.

## 테스트

```bash
./gradlew test
```

일반 테스트는 H2에서 Hibernate가 만든 스키마로 실행하며 Flyway를 쓰지 않습니다. PostgreSQL 마이그레이션 테스트는 Docker가 켜져 있으면 격리된 `postgres:16-alpine` 컨테이너에서 실행되고, Docker가 없으면 건너뜁니다. 스크립트나 엔티티를 바꿨을 때는 Docker를 켜고 `FlywayStartupTest`(빈 DB에서 기동)와 `CommonContractMigrationTest`·`ReceiptImageMigrationTest`(기존 DB 변경)가 건너뛰어지지 않았는지 확인합니다. S3 저장은 `S3StorageServiceTest`가 LocalStack 컨테이너(`localstack/localstack:3.8`)에서 검증하며 AWS 계정이 필요하지 않습니다.

프론트엔드 타입 검사와 빌드는 아래 명령으로 실행합니다.

```bash
cd frontend
npm run build
```

## 환경 설정

### local profile

- 기본 프로파일입니다.
- PostgreSQL에 연결합니다.
- 시작할 때 Flyway가 스키마를 적용하고 `spring.jpa.hibernate.ddl-auto=validate`로 검사합니다. 엔티티와 스키마가 다르면 앱 시작을 중단합니다.

### prod profile

운영 환경에서는 `prod` 프로파일을 사용하며 아래 환경 변수를 반드시 주입해야 합니다.

- `SPRING_PROFILES_ACTIVE=prod`
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_SECRET`

운영 프로파일도 로컬과 같이 Flyway 적용 후 `ddl-auto=validate`로 동작합니다.

### 이미지 저장소

- 영수증 이미지는 JPEG·PNG만 받으며 파일 내용으로 형식을 확인합니다. 한 장에 10MB, 영수증 하나에 3장까지입니다.
- `STORAGE_TYPE=local`(기본)은 `LOCAL_STORAGE_ROOT` 아래에 저장합니다.
- `STORAGE_TYPE=s3`는 `S3_BUCKET`의 비공개 객체로 저장합니다. `S3_BUCKET`이 없으면 앱이 시작하지 않습니다. 리전은 `AWS_REGION`(기본 `ap-northeast-2`)입니다.
- AWS 자격 증명은 SDK 기본 체인(인스턴스 역할, 환경 변수, 로컬 프로필)에서 읽습니다. 저장소와 `.env`에 키를 두지 않습니다.
- 삭제에 실패한 파일의 재시도 기록은 `STORAGE_CLEANUP_QUEUE`(기본 `LOCAL_STORAGE_ROOT/.cleanup-pending`)에 둡니다. S3를 쓸 때도 컨테이너를 다시 만들어도 남는 볼륨 경로여야 합니다.

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
- `POST /api/rooms/{roomId}/receipts/{receiptId}/images`
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
