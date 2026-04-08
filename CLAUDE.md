# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Local Development Setup
```bash
# Copy environment variables
cp .env.example .env

# Start PostgreSQL
docker compose up -d

# Run the application (local profile is default)
./gradlew bootRun

# Verify
curl http://localhost:8080/actuator/health
```

### Build & Test
```bash
./gradlew build          # Compile and package JAR
./gradlew test           # Run all tests (Testcontainers spins up its own PostgreSQL)
./gradlew test --tests "com.capstone.itemsplit.SomeSpecificTest"  # Run a single test class
```

## Architecture

**ItemSplit** is a Spring Boot 3.5.13 REST API (Java 21) for shared expense splitting — users join rooms, upload receipts with items, and items are assigned to members who owe for them.

### Domain Model

```
User (1)──(M) RoomMember (M)──(1) Room (1)──(M) Receipt (1)──(M) Item (1)──(M) Assignment (M)──(1) User
```

- **User** — registered account (email, password, nickname)
- **Room** — shared space with an owner user
- **RoomMember** — join table linking Users to Rooms (unique on room+user)
- **Receipt** — a purchase event inside a room
- **Item** — line item on a receipt (name, price, quantity)
- **Assignment** — maps an Item to a User who owes for it (unique on item+user)

All entities live under `src/main/java/com/capstone/itemsplit/domain/<domain>/`.

### Spring Profiles

| Profile | DB schema mode | When used |
|---------|---------------|-----------|
| `local` (default) | `ddl-auto: update` | Local dev with Docker PostgreSQL |
| `prod` | `ddl-auto: validate` | Production deployment |

Required env vars for production: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`.

### Testing

Tests use **Testcontainers** — a real PostgreSQL container is started automatically; no manual DB setup needed. The `TestcontainersConfiguration` class wires the container via `@ServiceConnection`.

## Business Rules

- 정산 결과는 DB에 저장하지 않고 요청 시 on-demand 계산
- Receipt에 `total_amount` 없음 — Item의 `price * quantity` 합산이 source of truth
- 반올림 오차는 방장(`Room.createdBy`)에게 배분

### Implementation Status

**구현 완료:**
- JPA 엔티티 (User, Room, RoomMember, Receipt, Item, Assignment)
- 공통 API 응답 래퍼 (`ApiResponse`, `ErrorResponse`)
- 예외 처리 (`ApiException`, `ErrorCode`, `GlobalExceptionHandler`)
- Spring Security + JWT 인증/인가 (`JwtTokenProvider`, `JwtAuthenticationFilter`)
- 회원가입/로그인 API (`POST /api/auth/signup`, `POST /api/auth/login`)
- 방 생성/조회/초대 API (`/api/rooms`)
- 영수증 수동 입력 CRUD (`/api/rooms/{roomId}/receipts/manual`, PUT, DELETE)
- 영수증 이미지 업로드 (`POST /api/rooms/{roomId}/receipts/image`)
- 품목 CRUD (`/api/rooms/{roomId}/receipts/{receiptId}/items`)
- 배정 API (`/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignments`)
- 총액 불일치 경고 (`warning` 필드, GET 영수증 단건 응답)
- Receipt `purchasedAt` (사용자 입력 구매 날짜), `payer`, `declaredTotal` 필드

**미구현:**
- 정산 계산 API (on-demand)
