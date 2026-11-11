# 두 개발자를 위한 공통 개발 계약

작성일: 2026-11-10. 5단계 상태 갱신: 2026-11-11. 기준: `feature/clova-receipt-settlement`의 공통 준비 작업과 사용자와 합의한 역할 분담.

활성화 설정은 [README의 정산 확정 API 개발 설정](../README.md#정산-확정-api-개발-설정)을, DB 적용 순서는 [마이그레이션 예약표](database-migration-reservations.md)를 따른다.

## 1. 현재 구현 상태와 후속 작업

| 구분 | 현재 상태 |
|---|---|
| 공통 HTTP 요청 | `frontend/src/httpClient.ts` 분리 및 기존 API·오류 클래스 호환 유지 |
| 정산 확정 1단계 | 확정본 저장 구조와 미리보기·확정·상태·확정본 조회 API 구현. API 기본 비활성화 유지 |
| 정산 확정 2단계 | 확정 화면·서버 편집 제한·재개방 구현. 재확정 시 새 버전 생성, 기존 기록 보존 |
| 정산 확정 3단계 | 현재 확정본의 송금 완료 표시/취소 API·화면 구현. 방장 전용, 명시적 상태 요청·감사 기록 멱등성 유지 |
| 정산 확정 4단계 | 현재 확정본의 공유 링크 발급·재발급, 비로그인 읽기 화면 구현. 특정 버전 고정·14일 만료·해시 저장·공개 정보 최소화 |
| 정산 확정 5단계 | 실제 PostgreSQL·인증 보안 필터를 사용하는 전체 수기 입력 HTTP 회귀 테스트 추가. 실제 nginx·API·PostgreSQL 브라우저 연결 검증 완료; 개발자 2·V3 통합 검증은 별도 출시 조건 |
| 마이그레이션 | V4 SQL 추가, V3는 개발자 2에게 예약. 공유·운영 DB 적용은 V3 통합 후 |
| 내 방 목록·보안·배정 제약 | 개발자 2의 후속 구현 |

개발자 1은 다음 순서로 진행한다.

1. **완료:** 확정본 데이터 구조·확정 API. 확정 시간은 DB TIMESTAMP(6)에 맞춰 마이크로초로 정규화한다.
2. **완료:** 확정 화면·편집 제한·재개방. 현재 브랜치의 입력 변경 경로에 guard를 연결한다. 개발자 2 변경 통합 시 멤버 연결과 최상위 화면의 연결을 다시 검증한다.
3. **완료:** 송금 완료 상태 변경 API·화면. 완료/취소는 명시적 boolean PUT이며 같은 상태 재전송은 변경자·시각을 유지한다.
4. **완료:** 현재 확정본 공유 링크·비로그인 화면. 발급·재발급은 현재 CONFIRMED 버전의 방장만 가능하며, 기존 과거 버전 링크는 만료 전까지 유지한다.
5. **현재 브랜치 전체 흐름 검증 완료:** `SettlementLifecyclePostgresApiTest`가 실제 PostgreSQL·MockMvc 보안 필터에서 방 생성·초대 참여·수기 영수증 입력부터 확정·입력 편집 차단·완료/취소 멱등성·익명 최소 응답·재발급 폐기·재개방·입력 수정·재확정·과거 금액/완료 보존·만료·방 삭제 후 링크 404까지 재현한다. Docker가 없으면 건너뛰므로 Docker 실행 상태에서 검증해야 한다. 별도 임시 DB에서 실제 nginx·API에 연결한 브라우저로 확정·편집 잠금·완료/취소·공유 재발급·비로그인 직접 열기/새로고침·재개방·수정 후 재확정·과거 완료/금액/이름 보존·방 삭제 후 링크 404를 검증했다. 모바일 과거 버전 화면도 확인했다. 현재 브랜치 검증을 개발자 2 변경·V3 통합 완료나 공유/운영 배포 승인으로 해석하지 않는다.

확정본 공유는 기존 V4 저장 구조를 사용하며 추가 마이그레이션이나 의존성은 없다. 활성화 설정은 편집 제한을 대신하지 않는다. 기존 실시간 정산·공유와 OCR·이미지 권한은 유지하며, 수정 요청·품목 이동 등의 별도 설계는 이번 범위에 포함하지 않는다.

## 2. 역할과 수정 파일 소유권

| 담당 | 기능 | 우선 소유 파일·영역 |
|---|---|---|
| 개발자 1 | 정산 확정·재개방, 송금 완료, 확정본 공유, 편집 제한과 전체 연결 검증 | 새 `settlement` 하위 클래스, 새 `frontend/src/features/settlement/`·`features/sharedSettlement/`, 기존 영수증·품목·배정·OCR 서비스의 guard 연결, V4 |
| 개발자 2 | 서버 방 목록, 요청 제한·JWT, 수동 멤버 계정 연결, 배정 DB 제약 | 새 방 목록 조회 서비스, `RoomService`·멤버 저장소, `frontend/src/features/rooms/`, 인증·요청 제한·운영 설정, V3 |
| 개발자 2 | 최상위 화면 연결 | `frontend/src/App.tsx`; 개발자 1은 컴포넌트와 props를 전달 |
| 공통 | 이미 분리한 HTTP 모듈·공통 타입·문서 | 기능별 API·타입은 새 디렉터리에 추가. 공통 변경이 필요하면 먼저 상대에게 파일·변경 범위를 알림 |

- 방·영수증은 기존 ID를 유지하고 참여자는 `RoomMember.id`를 사용한다. 로그인 계정 ID와 혼용하지 않는다.
- 내 방 목록은 정산 확정 상태·송금 완료율을 포함하지 않는다. 개발자 2는 새 정산 API 없이 완성할 수 있다.
- 개발자 1은 기존 방 ID·멤버·영수증으로 정산 기능을 개발한다. 새 방 목록 API를 기다리지 않는다.
- 2단계에서 현재 `RoomService`의 멤버 변경·참여·방 삭제에 §4 guard를 연결했다. 개발자 2의 동시 계정 연결 변경을 합칠 때도 방 잠금·권한 검사·guard 순서를 유지하고, 두 개발자가 멤버 경로를 함께 검토한다. `App.tsx`의 정산 화면·편집 상태 연결도 내 방 목록 변경과 병합할 때 유지한다.
- 기존 `RoomAuthorizationService.checkMemberForUpdate`에 편집 제한을 일괄 삽입하지 않는다. 이 메서드는 읽기 전용 공유 발급 등에도 사용되고, 확정 상태에서 허용할 완료·재개방 작업에도 필요하다.

## 3. 공통 HTTP 요청 계약 — 지금 사용 가능

파일: [httpClient.ts](../frontend/src/httpClient.ts).

```ts
export type RequestOptions = RequestInit & { token?: string | null };
export function request<T>(path: string, options?: RequestOptions): Promise<T>;
export class ApiError extends Error {
  status: number;
  reason?: string;
  retryAfterMs?: number;
}
export const API_BASE_URL: string;
```

위 코드는 공개 시그니처 요약이다. 실제 구현은 링크한 파일을 기준으로 한다.

| 항목 | 동작 |
|---|---|
| 주소 | `/api/...` 형태의 프로젝트 API 경로를 전달. 앞에 `VITE_API_BASE_URL`을 한 번 붙임 |
| 인증 | `token`이 있으면 Bearer 헤더 추가. 토큰을 자동으로 저장·조회·갱신하지 않음 |
| JSON | 호출자가 `JSON.stringify`한 body를 전달. body가 있고 명시적 Content-Type이 없으면 `application/json` 사용 |
| 이미지 업로드 | `FormData`를 그대로 전달. multipart Content-Type을 수동 지정하지 않음 |
| 성공 | 기존 `ApiEnvelope<T>`의 `data` 반환. 204는 `undefined` 반환 |
| 실패 | HTTP/응답 오류는 `ApiError`. `reason`은 `error.details[0].reason`, `retryAfterMs`는 Retry-After 초 또는 HTTP 날짜를 밀리초로 변환 |
| 응답 본문 오류 | HTML·잘못된 JSON은 HTTP 상태를 가진 ApiError로 처리. 본문 읽기 중 네트워크 오류는 원래 오류 유지 |
| 취소·재시도 | `AbortSignal` 전달, AbortError·네트워크 오류를 그대로 전파. 자동 재시도·자동 로그아웃·알림·라우팅은 하지 않음 |

새 기능별 API는 공통 모듈을 직접 사용한다. 아래 방 목록 함수는 사용 예시이며 아직 구현된 API가 아니다.

```ts
// frontend/src/features/rooms/api.ts
import { request } from '../../httpClient';
import type { RoomListResult } from './types';

export function getMyRooms(token: string, signal?: AbortSignal) {
  return request<RoomListResult>('/api/rooms', { token, signal });
}
```

기존 `frontend/src/api.ts`의 함수는 그대로 사용할 수 있다. `import { ApiError } from './api'`도 같은 클래스를 재노출하므로 OCR의 `instanceof` 분기가 유지된다. 새 기능에서 ApiError를 다시 정의하거나 기존 `api.ts`에 계속 기능을 추가하지 않는다. `API_BASE_URL`은 기존 상대 이미지 URL 보정에도 공유하며 API 호출 경로에 중복 적용하지 않는다.

## 4. 편집 제한 함수 호출 규약 — 2단계 구현

파일: `src/main/java/com/capstone/itemsplit/settlement/RoomEditGuard.java`.

```java
// 호출자는 같은 트랜잭션에서 방 잠금·권한 검사를 먼저 완료한다.
public void requireEditable(Room lockedRoom);
```

### 4.1 사전조건과 책임

1. 호출하는 public 서비스 메서드는 쓰기 `@Transactional`이어야 한다.
2. 같은 트랜잭션에서 방의 `PESSIMISTIC_WRITE` 잠금을 먼저 획득한다.
3. 로그인·방 접근·동작별 권한을 확인한다. 업로더 권한 확인 등에 필요한 최소 영수증·멤버 조회는 이 단계에서 허용하며, 대상이 해당 방에 속하는지도 검사한다. guard는 권한 검사를 대신하지 않는다.
4. guard가 최신 정산 상태를 조회해 `OPEN`이면 반환하고 `CONFIRMED`이면 구조화된 409 오류를 발생시킨다.
5. 그 뒤 나머지 대상 데이터 조회·검증을 완료하고 변경한다. 모든 DB 변경과 외부 효과는 guard 통과 이후에 시작한다. 잠금은 최종 커밋까지 유지한다.

guard 자체는 트랜잭션이 없으면 실패하도록 `Propagation.MANDATORY`로 구현한다. 별도 `REQUIRES_NEW` 트랜잭션이나 잠금을 풀고 실행하는 비동기 검사로 분리하지 않는다. 방 잠금을 이미 획득했다는 사전조건은 각 호출 경로의 코드·통합 테스트로 검증한다.

정산 상태 테이블이 없는 DB·조회 실패·알 수 없는 상태를 `OPEN`으로 간주하지 않는다. V4가 정상 적용된 후 아직 확정 이력이 없는 기존 방의 상태 행이 없는 경우에만 초기 `OPEN`으로 취급한다. 첫 확정 시에는 방 잠금 안에서 상태 행을 생성한다. API 활성화 플래그와 무관하게 이미 저장된 `CONFIRMED` 상태는 보호한다.

### 4.2 기존 방 멤버의 변경

```java
@Transactional
public void changeSomething(Long roomId, Long userId) {
    Room room = roomAuthorizationService.checkMemberForUpdate(roomId, userId);
    // 방장/업로더 등 이 동작에 필요한 추가 권한도 확인한다.
    roomEditGuard.requireEditable(room);
    // 대상 데이터 조회·검증·변경. 외부 효과도 guard 이후에 시작한다.
}
```

방 잠금 → 동작 권한 → 편집 가능 상태 → 하위 데이터 변경 순서를 지킨다. 이미 조회한 오래된 Room·정산 상태 캐시로 검사하지 않는다. 같은 트랜잭션에서 잠금 조회로 얻은 방과 최신 상태를 사용한다.

### 4.3 초대 참여는 멤버 검사와 구분

`RoomService.joinRoom`의 요청자는 아직 멤버가 아닐 수 있다. 여기에 `checkMemberForUpdate`를 사용하면 정상 참여가 거부된다.

- 인증과 초대 토큰으로 방 ID를 찾는다.
- `roomRepository.findByIdForUpdate(roomId)`로 방을 잠근다.
- 잠금 이후 토큰이 여전히 유효한지, 같은 방의 토큰인지, 현재 시점에 만료되지 않았는지 최신 상태로 재검증한다.
- 이미 참여한 같은 계정이면 기존 참여 결과를 반환할 수 있다. 이것은 변경 작업이 아니다.
- 신규 참여 또는 수동 멤버 연결이면 `requireEditable(room)`을 호출하고, 잠금 이후 멤버의 연결 여부를 다시 검사한 뒤 처리한다.
- 서로 다른 두 계정이 같은 멤버에 연결하려 하면 하나만 성공해야 한다.

### 4.4 오류 형식

HTTP 409의 응답을 다음 형태로 고정한다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "정산이 확정되어 수정할 수 없습니다. 재개방 후 다시 시도해 주세요.",
    "details": [
      { "field": "settlement", "rejectedValue": null, "reason": "SETTLEMENT_LOCKED" }
    ]
  }
}
```

현재 `ApiException`의 일반 처리기는 details를 빈 목록으로 반환한다. `new ApiException(CONFLICT, ...)`만 던져서는 위 계약이 충족되지 않는다. settlement 전용 예외·처리기가 reason을 제공한다. 공통 예외 처리기를 바꿀 필요가 있으면 개발자 2의 보안 변경과 조율한다. 프론트엔드는 문구 비교 대신 `error instanceof ApiError && error.reason === 'SETTLEMENT_LOCKED'`로 상태를 다시 조회하고 안내한다.

### 4.5 적용 지점과 연결 담당

| 현재 서비스·메서드 | 검사 위치 | 연결 담당 |
|---|---|---|
| `ReceiptService.createManualReceipt`, `updateReceipt`, `saveContents`, `deleteReceipt` | 방 잠금·권한 확인 후, DB 변경 전 | 개발자 1 |
| `ReceiptImageService.createReceiptWithImage`, `addImage` | 이미지 저장소 쓰기 전에 검사 | 개발자 1 |
| `ItemService.addItem`, `updateItem`, `deleteItem` | 방 잠금·권한 확인 후 | 개발자 1 |
| `AssignmentService.replaceAssignees` | 배정 삭제·재생성 전 | 개발자 1 |
| `OcrService.start`, `retry` | 작업 생성·호출 한도 예약·외부 호출 요청 전 | 개발자 1 |
| `OcrDraftService.save`, `apply` | 초안·실제 품목 변경 전. 기존 작성자·revision 검증 유지 | 개발자 1 |
| `OcrDemoService.create` | 데모 프로필도 같은 규칙 적용 | 개발자 1 |
| `RoomService.addManualMember`, `updateMemberName`, `deleteMember`, `deleteRoom` | 모두 방 쓰기 잠금 확보 후 검사. 방 삭제는 방장 권한 유지 | 개발자 2가 변경 경로 준비, 통합 시 개발자 1 연결 |
| `RoomService.joinRoom` | §4.3의 초대 참여 규칙 적용 | 개발자 2가 동시성 처리, 통합 시 개발자 1 연결 |
| 향후 방 이름 수정·이미지 개별 삭제·품목 이동 | 추가하는 시점에 같은 규칙 적용 | 해당 기능 작성자 |

`OcrWorker`는 요청자 인증 대신 시스템 작업으로 실행된다. 작업 결과 반영 트랜잭션도 같은 방 잠금을 먼저 얻고 작업 시도 번호·현재 상태를 재검증해야 한다. 확정 시 진행 중 작업 및 미해결·미반영 초안을 차단해 정상적으로는 결과 저장과 확정이 겹치지 않게 한다. 재개방·확정 이후 도착한 오래된 OCR 결과가 입력을 바꾸지 못하는 경쟁 테스트를 개발자 1이 담당한다. 사용량 반환·실패 상태 정리 같은 시스템 복구를 일반 사용자 guard로 일괄 차단하지 않는다.

조회, 이미지 URL 발급, 기존 공유 조회·발급, 초대 토큰 발급, 완료 표시, 재개방에는 편집 guard를 넣지 않는다. 완료·재개방은 별도의 방장·현재 확정본 검사와 방 잠금으로 보호한다. `checkMemberForUpdate`를 guard로 대체하거나 모든 사용처에 자동으로 삽입하지 않는다.

개발자 2는 정산 화면·API와 독립적으로 방 목록·보안·관계 제약·방 잠금 기반 연결 처리를 완성할 수 있다. 개발자 1은 두 사람의 변경을 합친 뒤 이 표의 모든 경로를 점검해야 확정 기능을 완료로 표시할 수 있다.

## 5. 정산 확정·완료·공유 기능 계약 — 개발자 1

### 5.1 상태·권한·금액

- 방 상태는 `OPEN`, `CONFIRMED`; 확정본 상태는 `CONFIRMED`, `REOPENED`.
- 방 멤버는 조회 가능, 방장만 확정·재개방·완료 표시/취소·확정본 공유 발급 가능.
- 현재 정산 검증을 통과하고 진행 중·미반영 OCR이 없어야 확정할 수 있다. 실패 사진은 기존 수동 보완·반영 흐름으로 해결한다. `OCR_TOTAL_MISMATCH` 경고만으로 확정을 차단하지 않는다.
- 금액은 원 단위 정수. 서버가 `paid`, `burden`, `net = paid - burden`과 송금 경로를 계산한다. 클라이언트 금액을 신뢰하지 않는다.
- 멤버 ID로 순서를 고정한 결정적 송금 계산을 사용한다. 송금 건수의 전역 최적화는 범위 밖이다. 모든 경로 금액은 양수이고 송금 후 각 멤버의 차액이 0이 되어야 한다.
- 완료 표시는 수동 기록이며 실제 은행 입금 확인이 아니다.

### 5.2 저장 모델

V4가 생성할 테이블의 예약 이름과 최소 데이터다. 실제 컬럼 타입·인덱스·제약·JPA 매핑은 개발자 1이 함께 구현한다.

| 테이블 | 데이터·필수 제약 |
|---|---|
| `room_settlement_states` | `room_id` 고유/PK, `status`, `current_snapshot_id`. 상태 행이 없고 이력이 없는 방은 OPEN. 기존 Room 엔티티에 상태 필드를 추가하지 않아 멤버 작업과 충돌을 줄임 |
| `settlement_snapshots` | ID, room ID, 방 이름의 확정 당시 값, 방별 version, status, 확정/재개방 계정·시각, request ID·요청 fingerprint. `(room_id, version)`·`(room_id, request_id)` 고유 |
| `settlement_snapshot_members` | snapshot ID, member ID, 확정 당시 표시 이름·paid·burden·net. `(snapshot_id, member_id)` 고유 |
| `settlement_transfers` | ID, snapshot ID, 송금/수취 member ID, 양수 amount, PENDING/COMPLETED, 변경자·시각 |
| `settlement_snapshot_share_tokens` | snapshot ID, 토큰 식별 정보, 만료 시각. 특정 버전에 결합 |

과거 확정본의 멤버 식별자는 현재 `room_members` 삭제에 연쇄 삭제되지 않게 한다. 이름·금액은 저장한 값으로 조회하고, 멤버 삭제 후에도 보존한다. 기존 영수증·품목을 수정하면서 확정본 금액을 덮어쓰지 않는다. 방 삭제는 OPEN에서만 가능하며, 명시적 방 삭제 시 확정본과 공유 토큰도 삭제한다. 이 연관 삭제는 개발자 1이 기존 방 삭제 흐름과 연결·검증한다.

재개방은 기존 확정본을 REOPENED로 만들고 방을 OPEN으로 바꾼다. `currentSnapshotId`는 OPEN에서 null, CONFIRMED에서 활성 확정본 ID다. 과거 확정본 ID로의 조회는 계속 가능하다. 재확정 시 새 버전을 생성하고 완료 표시는 전부 PENDING부터 시작한다. 과거 완료 기록은 보존하되 더 이상 수정할 수 없다. 방이 이미 OPEN이고 요청한 확정본이 가장 최근의 REOPENED 버전이면 재개방 재시도는 200과 `OPEN`·`currentSnapshotId: null`을 반환한다. 더 오래된 버전 또는 다른 활성 버전은 409, 다른 방의 확정본은 404다.

### 5.3 API

아래는 전체 목표 경로다. 1단계의 상태·미리보기·확정·확정본 조회, 2단계의 재개방, 3단계의 송금 완료 변경, 4단계의 확정본 공유를 구현했다. 기존 `GET /api/rooms/{roomId}/settlements`와 기존 공유 API는 유지한다. 모든 새 API도 기존 ApiResponse envelope를 따른다.

| 메서드·경로 | 용도 |
|---|---|
| `GET /api/rooms/{roomId}/settlement-state` | `roomId`, `status`, nullable `currentSnapshotId` 조회 |
| `GET /api/rooms/{roomId}/settlements/confirmation-preview` | 최신 정산·송금 경로와 `previewToken`, `ready`, `issues` 조회 |
| `POST /api/rooms/{roomId}/settlement-snapshots` | `{requestId, previewToken}`으로 확정. 최초 201, 동일 요청 재전송 200과 같은 확정본 |
| `GET /api/rooms/{roomId}/settlement-snapshots/{snapshotId}` | 해당 방의 특정 확정본·멤버 금액·송금 상태 조회 |
| `POST /api/rooms/{roomId}/settlement-snapshots/{snapshotId}/reopen` | 본문 없이 현재 확정본 재개방. 변경 후 방 상태 반환 |
| `PUT /api/rooms/{roomId}/settlement-snapshots/{snapshotId}/transfers/{transferId}/completion` | `{completed: true/false}`. 변경 후 송금 내역 반환. 토글 API 금지 |
| `POST /api/rooms/{roomId}/settlement-snapshots/{snapshotId}/share-token` | token, expiresAt, shareUrl 반환 |
| `GET /api/shared/settlements/{token}` | 비로그인 확정본·완료 상태 읽기 |

previewToken은 서버에서 정규화한 입력을 식별하고 방에 결합한다. 확정 시 같은 방 잠금 안에서 멤버·표시 이름·결제자·영수증·품목·배정·제외 여부·OCR 반영 상태를 다시 확인한다. 입력이나 방의 확정/재개방 세대가 바뀌면 이전 previewToken을 거부한다. 금액 합계만 비교하면 안 된다.

완료 요청의 `completed`는 JSON boolean만 허용하며 누락·null·문자열·숫자는 400이다. 현재 CONFIRMED 확정본만 변경할 수 있다. 같은 상태 재전송은 변경자·시각을 유지하고 최초 PENDING에 false를 보내면 감사 필드는 null로 유지한다. 실제 변경과 완료 취소는 변경자와 마이크로초 시각을 기록한다. 완료와 재개방은 같은 방 잠금으로 직렬화한다. 화면은 요청 중 중복 동작을 막고 응답 유실 시 반대 동작으로 바꾸지 않고 같은 목표 상태를 재시도한다.

동일 requestId·동일 요청의 재전송은 기존 확정본을 반환한다. requestId를 다른 payload에 재사용하면 409다. 확정 후 재개방된 과거 요청을 재전송해도 새 버전을 생성하거나 방을 다시 잠그지 않고 원래 확정본을 반환한다. 다른 방의 snapshotId·transferId는 거부한다.

| 상황 | HTTP | `error.code` / 첫 detail의 `reason` |
|---|---|---|
| 입력 누락·미반영 OCR | 400 | `VALIDATION_ERROR` / `SETTLEMENT_NOT_READY` |
| 일반 멤버의 방장 전용 동작 | 403 | `FORBIDDEN` / 사유가 필요하면 같은 이름 사용 |
| 미리보기 이후 입력 변경 | 409 | `CONFLICT` / `SETTLEMENT_INPUT_CHANGED` |
| 확정된 방 편집 | 409 | `CONFLICT` / `SETTLEMENT_LOCKED` |
| 과거 확정본 변경·다른 활성 버전 | 409 | `CONFLICT` / `SETTLEMENT_VERSION_CONFLICT` |
| requestId의 다른 payload 재사용 | 409 | `CONFLICT` / `IDEMPOTENCY_KEY_REUSED` |

프론트엔드는 reason을 기준으로 분기한다. 일반 ApiError에는 reason이 없을 수 있으므로 HTTP 상태·기본 오류 안내도 유지한다.

### 5.4 화면·공유

- 정산 화면은 `roomId`, `token`을 받아 자체 상태·확정본 API를 조회한다. API가 비활성화된 경우 유효한 방의 상태 조회 404에 한해 기존 정산 화면을 사용한다. 단, 이미 확인한 확정 상태 또는 `SETTLEMENT_LOCKED` 오류는 서버가 OPEN을 반환하기 전까지 잠금으로 유지한다. 다른 조회 오류나 상태 확인 중에는 편집을 허용하지 않는다. 버튼 가시성은 서버가 검증하는 권한의 대체 수단이 아니다.
- 미리보기 → 방장 확인 → 확정 → 완료 표시 → 필요 시 재개방 흐름을 제공한다. 409면 상태를 새로 읽고 입력을 재검토하게 한다.
- 공유 URL은 `/share/settlements/{token}`. 직접 접속·새로고침을 위한 SPA fallback을 검증한다.
- 링크는 특정 snapshotId에 고정한다. 재개방된 과거 버전은 그 사실을 표시하며 새 버전으로 조용히 바꾸지 않는다.
- 공유 만료는 기본 14일. 현재 CONFIRMED 확정본만 발급할 수 있고 과거 버전 발급은 `SETTLEMENT_VERSION_CONFLICT` 409다. 같은 확정본에서 재발급하면 이전 토큰을 폐기하며 방 잠금으로 발급·재개방·완료 변경과 직렬화한다. 원문은 32바이트 난수의 base64url이며 DB에는 SHA-256 해시만 저장한다.
- 공개 응답은 방 이름·snapshotId·version·status·확정/재개방/만료 시각·멤버 ID/저장된 이름/금액·송금 멤버 참조/금액/상태만 포함한다. 원본 영수증 이미지·이메일·로그인 계정 ID·내부 감사 정보·요청 지문은 포함하지 않는다. 존재하지 않거나 만료/폐기된 토큰은 모두 404이며 공개 UI는 같은 링크 안내를 표시한다.
- 공개 UI는 로컬 로그인 정보 조회 전에 경로를 분기하고 인증 헤더 없이 조회한다. 읽는 중/실패 시 이전 확정본을 비우며 완료 변경·재개방 등의 쓰기 UI는 없다. 방장은 발급 결과의 만료·복사·직접 복사 입력을 사용할 수 있고 방/계정/확정본 변경 시 발급 결과를 버린다. 409면 최신 상태를 조회한다.
- 서버의 공유 발급·조회 응답은 오류까지 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 사용한다. 프론트 nginx는 공유 화면 직접 접속/새로고침 fallback과 공유 URL의 access/error 로그 비활성화를 제공한다. 공유 HTML은 referrer 메타 정책도 적용한다. 추가 프록시나 관측 도구에서도 토큰 URL·본문을 수집하지 않는다.
- 새 공유 API는 기존 `/api/shared/**` 읽기 권한과 연결한다. 새 쓰기 경로를 비로그인으로 허용하지 않는다.

## 6. 서버 기반 내 방 목록 계약 — 개발자 2

`GET /api/rooms`, Bearer 인증 필수. 클라이언트의 userId를 받지 않고 인증된 계정과 연결된 RoomMember로 조회한다.

```ts
// 예정 파일: frontend/src/features/rooms/types.ts
export type RoomListItem = {
  roomId: number;
  roomName: string;
  myMemberId: number;
  owner: boolean;
  createdAt: string;
};
export type RoomListResult = { rooms: RoomListItem[] };
```

응답은 `{success: true, data: {rooms: [...]}, error: null}`. 방이 없으면 200과 빈 배열, 인증 실패는 401. `roomId` 내림차순, 방당 한 행, 첫 버전은 페이지네이션 없음. 날짜는 기존 서버 직렬화 형식을 따른다. 계정 없는 수동 멤버의 이름으로 계정과 연결했다고 추측하지 않는다.

예정 화면 계약:

```ts
export type RoomListProps = {
  rooms: RoomListItem[];
  selectedRoomId: number | null;
  loading: boolean;
  error: string | null;
  onSelect: (roomId: number) => void;
  onRetry: () => void;
};
```

- 로그인·세션 복원 후 조회, 생성·참여·삭제 성공 후 갱신.
- 조회 중/정상 목록/빈 목록/오류·재시도 상태를 구분한다. 네트워크 실패를 빈 목록으로 표시하지 않는다.
- 계정별 마지막 선택 방 ID만 로컬에 저장한다. 서버 목록에 있으면 복원, 없으면 첫 방, 빈 목록이면 null.
- 로그아웃·계정 전환 시 즉시 목록과 선택을 비운다. AbortSignal과 요청 세대 검사를 함께 사용해 이전 계정 응답이 늦게 도착해도 반영하지 않는다.
- 과거 localStorage 방 목록은 서버의 참여 관계로 자동 등록하지 않고, 실패 시 정상 목록인 것처럼 대체 표시하지 않는다.
- 개별 방 API는 목록 포함 여부와 별개로 기존 접근 권한 검사를 계속 수행한다.

## 7. 마이그레이션 배정

상세 절차·충돌 방지 기준은 [마이그레이션 예약표](database-migration-reservations.md)를 따른다.

| 버전 | 담당 | 예약 파일명 | 내용 |
|---|---|---|---|
| V1·V2 | 기존 | `V1__baseline.sql`, `V2__ocr_daily_usage.sql` | 이미 존재. 변경 금지 |
| V3 | 개발자 2 | `V3__assignment_member_required.sql` | 배정 멤버 필수 관계·관련 기존 데이터 사전 점검 |
| V4 | 개발자 1 | `V4__settlement_snapshots.sql` | 방 정산 상태·확정본·확정 멤버·송금 완료·공유 토큰 |

공유 DB에는 V3와 V4를 함께 포함한 릴리스를 V3 → V4 순서로 적용한다. V4만 적용한 개인 DB는 통합 전에 필요한 데이터를 별도 보존한 뒤 새 통합 DB로 옮긴다. 낮은 V3를 뒤늦게 넣기 위해 out-of-order·repair·기존 체크섬 변경을 기본 해결책으로 사용하지 않는다.

## 8. 완료 기준과 통합 점검

개발자 1: 확정 요청 중복·입력 변경 충돌, 완료 상태 멱등성·권한·버전, 확정 후 모든 입력 경로 차단, 재개방·재확정 시 과거 금액/완료 기록 보존, 만료/재발급/과거 버전 공유, OCR 작업과 확정 경쟁을 검증한다.

개발자 2: 다른 기기·계정의 방 목록, 계정 전환 중 늦은 응답, 같은 멤버에 대한 PostgreSQL 동시 연결, 잘못된 JWT 설정 기동 거부, 프록시 헤더 조작 제한 우회, NOT NULL과 미배정 초안의 공존을 검증한다.

공동: V1→V4 빈 PostgreSQL 기동과 V2→V3→V4 변경, 방 참여→영수증 입력→확정→편집 차단→완료 표시→공유→재개방→재확정을 검증한다. 확정과 멤버 연결·삭제·OCR 반영이 경쟁해도 같은 방 잠금으로 직렬화되어야 한다. 새 기능을 배포하기 전에는 guard와 호출 경로를 한 번에 연결한다.

공통 요청 분리 검증 명령은 `frontend`에서 `npm test`, `npm run build`다. 이 테스트 통과가 아직 구현하지 않은 정산·방 목록 API의 완료를 의미하지는 않는다.
