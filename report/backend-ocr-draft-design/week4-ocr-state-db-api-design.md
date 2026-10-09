# 이미지·OCR 상태 관리, DB 변경안, 초안 저장·반영 API 설계

영수증 이미지의 OCR 처리 상태, 검토 초안을 담을 테이블, 인식 시작부터 영수증 반영까지의 API와 트랜잭션 경계를 정리한다. 기준 코드는 `main` 5c4e7d2다. 요구사항은 [OCR 추출·수정 화면 요구사항](../backend-ocr-design/ocr-extraction-review-requirements.md)과 [CLOVA OCR 연동 설계](../backend-ocr-design/clova-ocr-integration-design.md), 이미지 저장은 [S3 저장 설계](../backend-s3-image-design/week3-s3-design.md), 금액·저장 규칙은 [공통 계약](../../docs/api-db-contract.md)을 따른다. 확정되지 않은 전제는 **[가정]** 으로 표시했다.

## 0. 결정 요약

| # | 항목 | 결정 | 다시 볼 조건 |
|---|---|---|---|
| 1 | OCR 처리 방식 | **비동기.** 인식 시작은 바로 응답하고, 서버 안의 작업 스레드가 사진을 한 장씩 처리한다. 화면은 상태 조회를 반복한다 | 서버를 2대 이상으로 늘릴 때 |
| 2 | 초안 저장 위치 | 정상 `items`와 분리한 OCR 전용 테이블 | — |
| 3 | 공급자 원문 응답 | 전체 응답은 저장하지 않는다. 변환한 후보와 필드별 원문·신뢰도·좌표만 보관한다 | 변환 오류 추적이 어려울 때 |
| 4 | 영수증 수정 버전 | 서비스가 직접 올리는 `content_version` | — |
| 5 | 반영 방식 | 서버에 저장된 초안을 그대로 반영한다(요청 본문에 품목 없음). 영수증의 기존 품목은 초안 내용으로 전체 교체한다 | 기존 품목에 덧붙이는 방식이 필요할 때 |
| 6 | 중복 반영 방지 | 방 잠금 안에서 초안 상태를 `EDITING → APPLIED`로 바꾼다. `items.ocr_draft_item_id` 유일 제약을 안전망으로 둔다 | — |
| 7 | 초안 동시 편집 | 초안에 `revision`을 두고 저장·반영 때 비교한다. 처리 중인 작업의 초안은 저장·반영할 수 없다 | 실시간 동시 편집을 도입할 때 |

## 1. 흐름과 구성

```
이미지 업로드(최대 3장) ─▶ 인식 시작 ─▶ 대기 ─▶ 처리 중 ─▶ 검토 가능 / 일부 실패 / 실패
                                                  │                    │
                                        사진별 공급자 호출        실패 사진 재시도
                                                  ▼                    ▼
                                        검토 초안(편집 중) ── 초안 저장(반복) ──▶ 영수증에 반영 ──▶ 반영 완료
                                                                                  └ items·assignments 생성
```

| 구성 | 책임 |
|---|---|
| `ocr.engine` | 이미지 한 장을 받아 공급자를 호출하고 내부 후보로 변환한다. DB와 권한을 알지 못한다 |
| `ocr` | 작업·사진별 상태, 초안 저장, 버전 검사, 영수증 반영 |
| `receipt` | 이미지 목록과 버전, 정상 품목 저장 검증(반영 때 재사용) |
| `storage` | 저장된 이미지의 바이트 읽기 |

## 2. 상태

### 작업 상태 `OcrJobStatus`

| 값 | 의미 | 정해지는 조건 |
|---|---|---|
| `PENDING` | 대기 | 작업을 만들었고 아직 어떤 사진도 시작하지 않음 |
| `PROCESSING` | 처리 중 | 대기·처리 중인 사진이 하나라도 있음 |
| `REVIEWABLE` | 검토 가능 | 모든 사진 성공 |
| `PARTIALLY_FAILED` | 일부 실패 | 성공한 사진과 실패한 사진이 함께 있음 |
| `FAILED` | 실패 | 모든 사진 실패 |

작업 상태는 사진별 상태에서 계산해 저장한다. 실패 사진을 재시도하면 `PROCESSING`으로 돌아갔다가 다시 정해진다.

### 사진별 상태 `OcrImageStatus`

`PENDING → PROCESSING → SUCCEEDED / FAILED`. 재시도는 `FAILED → PENDING`이다.

| 오류 코드 | 상황 | 재시도 |
|---|---|---|
| `TIMEOUT` | 연결·응답 시간 초과 | 가능 |
| `RATE_LIMITED` | 공급자 호출 제한 | 가능 |
| `PROVIDER_ERROR` | 공급자 일시 오류 | 가능 |
| `IMAGE_UNAVAILABLE` | 저장소에서 이미지를 읽지 못함 | 가능 |
| `INTERRUPTED` | 서버 재시작 등으로 처리가 끊김 | 가능 |
| `CONFIG_ERROR` | 인증·설정 오류 | 불가 (서버 설정 필요) |
| `RECOGNITION_FAILED` | 응답은 받았으나 인식 실패 | 불가 (재촬영 안내) |
| `EMPTY_RESULT` | 품목이 하나도 없음 | 불가 (재촬영·수기 입력 안내) |
| `UNSUPPORTED_INPUT` | JPEG·PNG가 아님 | 불가 (삭제 후 다시 업로드) |

품목이 없는 결과는 성공으로 두지 않고 `EMPTY_RESULT` 실패로 기록한다. 자동 재시도는 하지 않는다.

### 초안 상태 `OcrDraftStatus`

| 값 | 의미 |
|---|---|
| `EDITING` | 편집 중. 저장·반영 가능 |
| `APPLIED` | 반영 완료. 더 이상 바뀌지 않음 |
| `DISCARDED` | 같은 영수증에서 새 인식을 시작해 대체됨. 내용은 남아 있고 읽기만 가능 |

재인식은 새 작업과 새 초안을 만든다. 이전 초안의 편집 내용과 이미 반영된 품목은 건드리지 않는다.

## 3. DB 변경안

enum은 기존 `ReceiptSourceType`처럼 문자열로 저장한다. 금액은 `BIGINT`, 수량은 `INTEGER`다.

### 3.1 기존 테이블에 추가하는 컬럼

| 테이블 | 컬럼 | 타입 | 용도 |
|---|---|---|---|
| `receipts` | `image_list_version` | `INTEGER NOT NULL DEFAULT 0` | 이미지를 추가·삭제할 때마다 1 증가 |
| `receipts` | `content_version` | `BIGINT NOT NULL DEFAULT 0` | 메타정보·품목·참여자가 바뀔 때마다 1 증가 |
| `items` | `ocr_draft_item_id` | `BIGINT NULL`, 유일 | 이 품목을 만든 초안 품목. 수기 품목은 `NULL` |

`content_version`에 JPA `@Version`을 쓰지 않는 이유는, `@Version`은 `receipts` 행이 바뀔 때만 올라가서 품목이나 참여자만 바뀐 경우를 잡지 못하기 때문이다. 같은 방의 저장은 이미 방 잠금으로 직렬화되어 있으므로, 잠금 안에서 서비스가 값을 올리면 된다.

### 3.2 `receipt_images`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | 응답의 `imageId` |
| `receipt_id` | `BIGINT NOT NULL` FK | |
| `sort_order` | `INTEGER NOT NULL` | 영수증 안에서 `최댓값 + 1`. 삭제해도 다시 매기지 않음 |
| `stored_path` | `VARCHAR(512) NOT NULL` | 객체 키. 응답에 노출하지 않음 |
| `original_filename` | `VARCHAR(255)` | |
| `content_type` | `VARCHAR(50) NOT NULL` | 검사로 확인한 형식 |
| `file_size` | `BIGINT NOT NULL` | |
| `created_at` | `TIMESTAMP NOT NULL` | |

유일 제약 `(receipt_id, sort_order)`. 응답의 `order`는 `sort_order` 순으로 1부터 다시 센 값이다.

### 3.3 `ocr_jobs` — 인식 작업

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | `jobId` |
| `receipt_id` | `BIGINT NOT NULL` FK | |
| `request_id` | `VARCHAR(64) NOT NULL` | 화면이 만든 식별자. 같은 인식 시작 요청의 재전송을 구분 |
| `image_list_version` | `INTEGER NOT NULL` | 작업을 만든 시점의 이미지 목록 버전 |
| `status` | `VARCHAR(20) NOT NULL` | `OcrJobStatus` |
| `provider` | `VARCHAR(30) NOT NULL` | 사용한 공급자 (`CLOVA_RECEIPT` 등) |
| `requested_by_user_id` | `BIGINT NOT NULL` | 시작한 사용자 |
| `created_at`, `started_at`, `finished_at` | `TIMESTAMP` | |

유일 제약 `(receipt_id, request_id)`. 한 영수증에서 `PENDING`·`PROCESSING` 작업은 하나만 허용하며, 방 잠금 안에서 검사한다.

### 3.4 `ocr_job_images` — 사진별 상태

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | |
| `job_id` | `BIGINT NOT NULL` FK | |
| `image_id` | `BIGINT NOT NULL` | 작업 시점의 `receipt_images.id`. 이후 이미지가 삭제될 수 있어 FK를 걸지 않음 |
| `sort_order` | `INTEGER NOT NULL` | 작업 시점의 이미지 순서 |
| `status` | `VARCHAR(20) NOT NULL` | `OcrImageStatus` |
| `error_code` | `VARCHAR(30)` | 2장의 오류 코드 |
| `attempt_count` | `INTEGER NOT NULL DEFAULT 0` | 공급자 호출 횟수 |
| `duration_ms` | `INTEGER` | 마지막 호출 소요 시간 |
| `store_name` | `VARCHAR(255)` | 상호명 후보 |
| `purchased_at` | `DATE` | 거래 날짜 후보 |
| `total_amount` | `BIGINT` | 총액 후보 |
| `candidate_detail` | `TEXT` (JSON) | 위 세 후보의 원문·신뢰도·좌표 |
| `manually_covered` | `BOOLEAN NOT NULL DEFAULT FALSE` | 실패한 사진의 범위를 수기로 보완했다는 사용자 확인 |
| `started_at`, `finished_at` | `TIMESTAMP` | |

유일 제약 `(job_id, image_id)`. 총액 후보는 사진마다 하나씩 보관하고 합산하지 않는다.

### 3.5 `ocr_drafts` — 검토 초안

작업 하나에 초안 하나다. 작업을 만들 때 함께 만들고, 영수증의 현재 이름·결제자·총액·날짜로 채운다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | `draftId` |
| `job_id` | `BIGINT NOT NULL` FK, 유일 | |
| `receipt_id` | `BIGINT NOT NULL` FK | |
| `status` | `VARCHAR(20) NOT NULL` | `OcrDraftStatus` |
| `revision` | `BIGINT NOT NULL DEFAULT 0` | 초안 저장마다 1 증가 (`@Version`) |
| `base_content_version` | `BIGINT NOT NULL` | 초안이 기준으로 삼는 영수증 `content_version` |
| `name` | `VARCHAR(100)` | 사용자 편집값 |
| `payer_member_id` | `BIGINT` FK | 해당 방의 `RoomMember.id` |
| `declared_total` | `BIGINT` | |
| `purchased_at` | `DATE` | |
| `applied_at` | `TIMESTAMP` | |
| `created_at`, `updated_at` | `TIMESTAMP` | |

### 3.6 `ocr_draft_items` — 초안 품목

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | `draftItemId` |
| `draft_id` | `BIGINT NOT NULL` FK | |
| `image_id` | `BIGINT` | 출처 사진. 사용자가 추가한 행은 `NULL` |
| `row_index` | `INTEGER` | 사진 안에서의 행 순서 |
| `sort_order` | `INTEGER NOT NULL` | 화면 표시 순서 (이미지 순서 → 행 순서) |
| `ocr_name` | `VARCHAR(255)` | 인식한 품목명. 이후 바뀌지 않음 |
| `ocr_quantity` | `INTEGER` | 인식한 수량 |
| `ocr_unit_price` | `BIGINT` | 인식한 단가 |
| `ocr_line_amount` | `BIGINT` | 인식한 행 금액 |
| `ocr_flags` | `VARCHAR(255)` | 변환 단계에서 붙인 표시. 쉼표 구분 |
| `ocr_detail` | `TEXT` (JSON) | 필드별 원문·신뢰도·좌표 |
| `name` | `VARCHAR(255)` | 사용자 편집값 |
| `price` | `BIGINT` | 사용자 편집값 (단가) |
| `quantity` | `INTEGER` | 사용자 편집값 |
| `excluded_from_settlement` | `BOOLEAN NOT NULL DEFAULT FALSE` | |
| `removed` | `BOOLEAN NOT NULL DEFAULT FALSE` | 사용자가 삭제한 인식 행. 되돌릴 수 있게 행은 남김 |
| `duplicate_group` | `INTEGER` | 중복 의심 쌍은 같은 번호 |
| `duplicate_confirmed` | `BOOLEAN NOT NULL DEFAULT FALSE` | "둘 다 유지" 확인 |

`ocr_draft_item_members (draft_item_id, room_member_id)` 로 품목별 참여자를 저장한다. 두 컬럼이 PK다.

- 편집값의 초깃값은 인식한 품목명·수량·단가다. 값이 없으면 `NULL`로 두고 0이나 1로 채우지 않는다.
- 단가를 읽지 못했고 `행 금액 ÷ 수량`이 정수로 나누어떨어지면, 그 값을 `price`에 넣지 않고 응답의 `derivedUnitPrice`로만 알려 준다. 사용자가 채택해야 `price`가 된다.
- `ocr_flags` 값: `DISCOUNT_OR_REFUND`(할인·환불 행), `NON_INTEGER_QUANTITY`(소수 수량), `UNPARSED_NUMBER`(숫자로 해석 못 함). 이런 행은 해석할 수 없는 편집값을 `NULL`로 두어 사용자가 고치거나 지우게 한다.
- 누락·범위 초과·금액 불일치 같은 검토 사유는 저장하지 않고, 조회할 때 현재 편집값으로 계산한다.

### 3.7 삭제와 정리

| 동작 | 처리 |
|---|---|
| 영수증·방 삭제 | 같은 트랜잭션에서 `ocr_draft_item_members → ocr_draft_items → ocr_drafts → ocr_job_images → ocr_jobs → receipt_images` 순으로 삭제. 이미지 객체는 커밋 후 삭제 |
| 이미지 한 장 삭제 | `receipt_images` 행 삭제, `image_list_version` 증가. OCR 행은 지우지 않고 초안이 오래된 상태가 됨 |
| 방 멤버 삭제 | 그 멤버의 `ocr_draft_item_members` 삭제, 초안의 `payer_member_id`는 `NULL`. 초안의 결제자는 멤버 삭제를 막지 않음 |

### 3.8 기존 이미지 컬럼 이전

`receipts`의 `stored_path`, `original_filename`, `content_type`, `file_size`를 `receipt_images`로 옮긴다.

```sql
INSERT INTO receipt_images (receipt_id, sort_order, stored_path, original_filename, content_type, file_size, created_at)
SELECT r.id, 1, r.stored_path, r.original_filename, COALESCE(r.content_type, 'application/octet-stream'),
       COALESCE(r.file_size, 0), r.created_at
FROM receipts r
WHERE r.stored_path IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM receipt_images i WHERE i.receipt_id = r.id);

UPDATE receipts SET image_list_version = 1
WHERE stored_path IS NOT NULL AND image_list_version = 0;
```

1. 변경 스크립트 `db/migrations/002_receipt_images_ocr.sql`에서 새 테이블·컬럼을 만들고 위 복사를 수행한다. 기존 스크립트처럼 다시 실행해도 안전하게 작성한다.
2. 코드는 `receipt_images`만 읽고 쓴다. `receipts`의 네 컬럼은 남겨 두되 사용하지 않는다.
3. 배포 후 문제가 없으면 별도 스크립트로 네 컬럼을 삭제한다.

`NOT NULL` 컬럼 추가는 로컬 자동 스키마 갱신으로 처리되지 않으므로, 기존 DB는 앱 실행 전에 변경 스크립트를 먼저 적용한다. 이전에 올린 이미지의 `content_type`은 요청 헤더 값이라 실제 형식과 다를 수 있다. 인식 시작 때 형식을 다시 확인하고, JPEG·PNG가 아니면 그 사진만 `UNSUPPORTED_INPUT`으로 처리한다.

## 4. 처리 방식 — 비동기

| 비교 | 동기 (요청 안에서 호출) | 비동기 (채택) |
|---|---|---|
| 응답 시간 | 사진 3장을 차례로 호출하는 동안 요청이 열려 있음. 시간 초과가 겹치면 수십 초 | 인식 시작은 바로 응답 |
| 연결이 끊긴 경우 | 결과를 받지 못해 다시 조회할 수단이 따로 필요 | 상태 조회로 이어서 확인 |
| 사진별 진행 표시 | 끝난 뒤에만 알 수 있음 | 사진별 상태를 그대로 표시 |
| 요구사항의 `대기`·`처리 중` | 화면에서만 흉내 냄 | DB 상태와 일치 |
| 구현 부담 | 작음 | 작업 스레드, 중단 복구 필요 |

메시지 큐는 두지 않는다. 서버 안의 스레드 풀로 실행하고 상태는 DB에 둔다. **[가정: 서버 인스턴스 1대]**

### 실행 순서와 트랜잭션

| 단계 | 트랜잭션·잠금 | 하는 일 |
|---|---|---|
| ① 인식 시작 | 방 잠금 | 권한·이미지·진행 중 작업 확인, 작업·사진별 행·초안 생성. 커밋 후 작업 스레드에 넘김 |
| ② 사진 시작 | 짧은 트랜잭션 | 사진 `PROCESSING`, `attempt_count` 증가, 작업 `PROCESSING` |
| ③ 공급자 호출 | **트랜잭션 없음** | 저장소에서 바이트를 읽어 `ReceiptOcrEngine` 호출 |
| ④ 결과 기록 | 작업 행 잠금 | 사진 상태·후보 저장, 초안 품목 추가, 작업 상태 다시 계산 |
| ⑤ 마무리 | ④와 같은 트랜잭션 | 모든 사진이 끝났으면 사진 경계의 중복 의심 표시 |

- 한 작업의 사진은 순서대로 한 장씩 호출한다. 작업 스레드는 2개로 시작한다.
- ③ 동안 DB 잠금을 잡지 않는다. ④에서 작업 행이 없으면(영수증·방이 삭제됨) 결과를 버린다.
- ④는 방 잠금을 잡지 않는다. 영수증 삭제는 방 잠금 → 작업 행 순서이고 작업 스레드는 작업 행만 잠그므로 교착이 생기지 않는다.
- 중복 의심: 이웃한 두 사진에서 앞 사진의 마지막 3행과 뒷 사진의 첫 3행을 비교해, 품목명·수량·단가·행 금액 후보가 모두 같은 쌍에 같은 `duplicate_group`을 준다. 자동으로 지우거나 합치지 않는다. **[가정: 비교 범위 3행]**

### 중단 복구

- 앱 시작 시 `PENDING`·`PROCESSING`으로 남은 사진을 `FAILED`·`INTERRUPTED`로 바꾸고 작업 상태를 다시 계산한다.
- 실행 중에는 1분마다 확인해, 시작한 지 3분이 지나도 끝나지 않은 사진을 같은 방식으로 처리한다.
- 사용자는 실패 사진 재시도로 이어서 진행한다.

### 설정

```yaml
app:
  ocr:
    provider: ${OCR_PROVIDER:fake}      # fake | clova
    worker-threads: 2
    connect-timeout: 3s
    read-timeout: 15s
    stuck-after: 3m
    clova:
      invoke-url: ${CLOVA_OCR_INVOKE_URL:}
      secret: ${CLOVA_OCR_SECRET:}
```

`fake`는 정해진 합성 결과를 돌려주는 구현체로, 공급자 계정 없이 로컬 실행과 자동 테스트에 쓴다. `provider=clova`인데 주소나 키가 비어 있으면 앱 시작을 막는다. 운영 프로필에서는 `fake`로 시작하는 것도 막아, 설정 누락으로 가짜 결과가 나가지 않게 한다.

## 5. API

모든 경로는 `/api/rooms/{roomId}/receipts/{receiptId}/ocr` 아래에 있고, JWT와 방 멤버 권한을 요구한다. 공유 토큰으로는 열지 않는다. 방 멤버는 누구나 같은 초안을 보고 편집할 수 있다.

| 메서드·경로 | 동작 | 응답 |
|---|---|---|
| `POST /jobs` | 인식 시작 | 202, 작업 응답 |
| `GET /jobs/latest` | 가장 최근 작업과 초안. 없으면 `data: null` | 200 |
| `GET /jobs/{jobId}` | 작업 상태와 초안 (상태 확인용 반복 조회) | 200 |
| `POST /jobs/{jobId}/images/{imageId}/retry` | 실패한 사진 한 장 재시도 | 202, 작업 응답 |
| `PUT /jobs/{jobId}/draft` | 초안 저장 | 200, 작업 응답 |
| `POST /jobs/{jobId}/draft/apply` | 영수증에 반영 | 200, 기존 영수증 상세 응답 |

### 5.1 인식 시작

요청 `{ "requestId": "8d3f…" }`. 화면이 UUID를 만들고 같은 요청의 재전송에 다시 쓴다.

- 이미지가 한 장도 없으면 400.
- 같은 `requestId`가 다시 오면 기존 작업을 그대로 돌려준다.
- 다른 `requestId`인데 진행 중인 작업이 있으면 409 `JOB_IN_PROGRESS`.
- 같은 영수증의 `EDITING` 초안은 `DISCARDED`가 된다. 화면이 재인식 전에 이를 안내한다.

### 5.2 작업 응답

```json
{
  "jobId": 7,
  "status": "PARTIALLY_FAILED",
  "stale": false,
  "staleReasons": [],
  "receiptContentVersion": 4,
  "images": [
    { "imageId": 41, "order": 1, "status": "SUCCEEDED", "errorCode": null, "retryable": false,
      "manuallyCovered": false,
      "candidates": { "storeName": "○○식당", "purchasedAt": "2026-10-01", "totalAmount": 30000 } },
    { "imageId": 42, "order": 2, "status": "FAILED", "errorCode": "TIMEOUT", "retryable": true,
      "manuallyCovered": false, "candidates": null }
  ],
  "draft": {
    "draftId": 5,
    "status": "EDITING",
    "revision": 2,
    "name": "저녁 식사",
    "payerMemberId": null,
    "declaredTotal": null,
    "purchasedAt": null,
    "items": [
      { "draftItemId": 101, "imageId": 41, "rowIndex": 1,
        "ocr": { "name": "된장찌개", "quantity": 2, "unitPrice": null, "lineAmount": 16000,
                 "derivedUnitPrice": 8000, "flags": [], "detail": { } },
        "name": "된장찌개", "price": null, "quantity": 2,
        "memberIds": [], "excludedFromSettlement": false,
        "removed": false, "duplicateGroup": null, "duplicateConfirmed": false,
        "issues": ["MISSING_PRICE"], "warnings": [] }
    ],
    "itemsTotal": 0,
    "applicable": false,
    "blockers": ["IMAGE_FAILED_UNCOVERED", "ITEM_ISSUES"]
  }
}
```

- 화면은 작업이 `PENDING`·`PROCESSING`인 동안 `GET /jobs/{jobId}`를 2초 간격으로 조회하고, 그 외 상태가 되면 멈춘다. 기존 요청 제한은 IP당 60초에 120회이므로 한 사람의 조회(분당 30회)는 여유가 있다.
- `issues`(반영을 막음): `MISSING_NAME`, `MISSING_PRICE`, `MISSING_QUANTITY`, `PRICE_OUT_OF_RANGE`, `QUANTITY_OUT_OF_RANGE`, `LINE_AMOUNT_OUT_OF_RANGE`, `DUPLICATE_UNRESOLVED`, `EXCLUDED_WITH_MEMBERS`.
- `warnings`(반영을 막지 않음): `LINE_AMOUNT_MISMATCH`(단가 × 수량이 인식한 행 금액과 다름), `SPECIAL_LINE`(할인·환불·소수 수량 표시가 있는 행).
- `blockers`(초안 단위): `JOB_IN_PROGRESS`, `IMAGE_FAILED_UNCOVERED`, `IMAGES_CHANGED`, `RECEIPT_CHANGED`, `NO_ITEMS`, `ITEM_ISSUES`, `RECEIPT_TOTAL_LIMIT`.
- `itemsTotal`은 삭제하지 않은 행 중 단가·수량이 모두 있는 행의 합계다. 제외 품목도 포함한다.
- 중복 의심 쌍은 한 행을 삭제하거나 양쪽에 `duplicateConfirmed = true`를 저장하면 해결된다.

### 5.3 실패 사진 재시도

- `FAILED`이고 재시도 가능한 오류일 때만 받는다. 성공한 사진은 다시 호출하지 않는다.
- 그 사진이 이미 `PENDING`·`PROCESSING`이면 현재 상태를 그대로 돌려준다(중복 클릭).
- 이미지 목록이 바뀐 작업은 409 `IMAGES_CHANGED`.
- 재시도 결과는 같은 초안에 그 사진의 품목으로 추가된다. 기존 편집값은 바뀌지 않는다.

### 5.4 초안 저장

```json
{
  "revision": 2,
  "name": "저녁 식사",
  "payerMemberId": 12,
  "declaredTotal": 30000,
  "purchasedAt": "2026-10-01",
  "coveredImageIds": [42],
  "acknowledgedContentVersion": null,
  "items": [
    { "draftItemId": 101, "name": "된장찌개", "price": 8000, "quantity": 2,
      "memberIds": [12, 15], "excludedFromSettlement": false,
      "removed": false, "duplicateConfirmed": false },
    { "draftItemId": null, "name": "공깃밥", "price": 1000, "quantity": 2,
      "memberIds": [], "excludedFromSettlement": false,
      "removed": false, "duplicateConfirmed": false }
  ]
}
```

- `items`는 초안의 전체 목록이다. `draftItemId = null`은 사용자가 추가한 행이다. 인식 행은 목록에서 빼지 않고 `removed = true`로 보내며, 사용자가 추가한 행은 목록에서 빼면 삭제된다.
- 품목명·단가·수량은 `null`과 범위를 벗어난 값도 저장한다. 0 이상의 정수이고 컬럼 범위 안이면 받는다. 범위 문제는 `issues`로 알려 준다.
- 결제자·참여자 ID는 저장 시점에 검사한다. 해당 방의 `RoomMember.id`가 아니면 400이다.
- 인식한 값(`ocr_*`)과 출처는 이 요청으로 바뀌지 않는다.
- `coveredImageIds`는 실패한 사진 중 수기로 보완했다고 확인한 사진이다.
- `revision`이 현재 값과 다르면 409 `DRAFT_REVISION_MISMATCH`. 화면은 다시 조회해 다른 사람의 변경을 보여 준다.
- 작업이 진행 중이면 409 `JOB_IN_PROGRESS`, 초안이 `EDITING`이 아니면 409 `DRAFT_NOT_EDITABLE`.

### 5.5 영수증에 반영

요청 `{ "revision": 3 }`. 반영하는 내용은 서버에 저장된 초안이다.

반영 조건:

1. 초안이 `EDITING`이고 `revision`이 일치한다.
2. 작업이 진행 중이 아니다.
3. 작업의 `image_list_version`이 영수증의 현재 값과 같다.
4. 초안의 `base_content_version`이 영수증의 현재 `content_version`과 같다.
5. 실패한 사진이 없거나 모두 `manually_covered`다.
6. 삭제하지 않은 행이 하나 이상이고 `issues`가 없다.
7. 공통 계약의 금액 상한(품목·영수증·방 합계)을 통과한다.

결제자 미지정, 참여자 미지정, 총액 미입력·불일치는 반영을 막지 않는다. 공통 계약대로 저장은 되고 정산만 차단된다.

처리 순서(하나의 트랜잭션):

1. 방 잠금과 멤버 확인 (`checkMemberForUpdate`).
2. 초안이 이미 `APPLIED`이면 변경 없이 현재 영수증 상세를 돌려준다. 요청의 `revision`이 반영된 값과 다르면 409.
3. 위 조건 검사.
4. 삭제하지 않은 초안 품목을 정상 품목 저장 형식으로 바꿔 기존 전체 저장과 같은 검증·교체 로직을 실행한다. 새 품목에 `ocr_draft_item_id`를 기록한다.
5. 영수증 이름·결제자·총액·날짜 갱신, `content_version` 증가.
6. 초안 `APPLIED`, `applied_at` 기록.

반영 버튼을 여러 번 누르거나 응답을 받지 못해 다시 보내도 2번에서 걸러져 품목이 늘지 않는다.

### 5.6 충돌 응답

`ErrorCode`에 `CONFLICT`(HTTP 409)를 추가한다. 화면이 구분할 수 있도록 사유를 `error.details[].reason`에 담는다.

| 사유 | 상황 | 화면 안내 |
|---|---|---|
| `JOB_IN_PROGRESS` | 처리 중인 작업이 있음 | 완료까지 대기 |
| `IMAGES_CHANGED` | 인식 후 이미지를 추가·삭제함 | 다시 인식 |
| `RECEIPT_CHANGED` | 초안 생성 후 영수증이 수정됨 | 현재 영수증 확인 후 계속 |
| `DRAFT_REVISION_MISMATCH` | 다른 사람이 초안을 먼저 저장함 | 다시 불러오기 |
| `DRAFT_NOT_EDITABLE` | 반영 완료·폐기된 초안 | 최신 작업 조회 |

## 6. 버전과 오래된 초안

| 비교 | 다르면 | 해결 |
|---|---|---|
| `ocr_jobs.image_list_version` ↔ `receipts.image_list_version` | `stale = true`, `IMAGES_CHANGED`. 재시도·반영 불가, 초안 저장은 가능 | 새 인식 시작 |
| `ocr_drafts.base_content_version` ↔ `receipts.content_version` | `stale = true`, `RECEIPT_CHANGED`. 반영 불가 | 사용자가 현재 영수증을 확인한 뒤 초안 저장 요청에 `acknowledgedContentVersion`(작업 응답의 `receiptContentVersion`)을 보내면 기준 버전을 갱신 |

- `image_list_version`을 올리는 곳: 이미지 추가, 이미지 삭제.
- `content_version`을 올리는 곳: 영수증 메타정보 수정, 전체 저장, 개별 품목 생성·수정·삭제, 품목 참여자 지정, OCR 반영.
- 영수증이 수정됐을 때 공급자를 다시 호출하게 하지 않는 이유는 인식 결과 자체는 그대로 유효하기 때문이다. 사진이 바뀐 경우는 결과가 사진과 맞지 않으므로 다시 인식한다.

## 7. 내부 인터페이스

### 공급자 호출과 변환

```java
public interface ReceiptOcrEngine {

    OcrImageResult recognize(OcrImageInput input) throws OcrEngineException;

    record OcrImageInput(byte[] content, String contentType, String filename) { }

    record OcrImageResult(OcrField<String> storeName, OcrField<LocalDate> purchasedAt,
        OcrField<Long> totalAmount, List<OcrLine> lines) { }

    record OcrLine(OcrField<String> name, OcrField<Integer> quantity, OcrField<Long> unitPrice,
        OcrField<Long> lineAmount, Set<OcrLineFlag> flags) { }

    record OcrField<T>(String rawText, T value, Double confidence, List<Point> box) { }
}
```

- 필드를 읽지 못했으면 `OcrField` 자체가 `null`, 읽었지만 해석하지 못했으면 `value = null`이고 `rawText`만 채운다.
- `OcrEngineException`은 2장의 오류 코드를 담는다. 품목이 없는 결과는 예외가 아니라 빈 `lines`로 돌려주고, 호출한 쪽이 `EMPTY_RESULT`로 기록한다.
- 구현체는 이미지 한 장만 다루며 DB·영수증·방을 알지 못한다. 카드번호·승인번호 등 정산과 무관한 값은 결과에 담지 않는다.
- 사진 사이의 중복 의심 표시와 계산 단가 후보는 이 인터페이스 밖(초안 쪽)에서 처리한다.

### 저장소 읽기

```java
public interface StorageService {
    StoredFile store(String directory, MultipartFile file) throws IOException;
    void delete(String storedPath) throws IOException;
    byte[] read(String storedPath) throws IOException;   // 추가
}
```

장당 최대 10MB이므로 바이트 배열로 읽는다.

### 품목 저장 검증의 공유

`ReceiptService`의 `validateCommands`·`replaceItems`를 별도 컴포넌트로 옮겨, 전체 저장(`/contents`)과 OCR 반영이 같은 검증·교체 로직을 쓰게 한다.

## 8. 기존 코드에서 바뀌는 곳

| 위치 | 변경 |
|---|---|
| `Receipt` | `imageListVersion`, `contentVersion` 추가. 이미지 컬럼 4개는 사용 중단 |
| `ReceiptService`, `ItemService`, `AssignmentService` | 영수증 내용을 바꾸는 경로에서 `content_version` 증가 |
| `ReceiptService.deleteReceipt`, `RoomService.deleteRoom` | OCR 행과 `receipt_images` 삭제, 이미지 객체 정리 대상을 `receipt_images`에서 가져옴 |
| `RoomService.deleteMember` | 초안의 참여자·결제자 정리 |
| `Item` | `ocrDraftItemId` 추가 |
| `ErrorCode` | `CONFLICT` 추가 |
| `StorageService`와 구현체 | `read` 추가 |

## 9. 확인할 동작

| 상황 | 기대 결과 |
|---|---|
| 같은 `requestId`로 인식 시작 두 번 | 작업 하나, 공급자 호출도 한 번씩 |
| 3장 중 1장 실패 | `PARTIALLY_FAILED`, 성공한 사진의 품목 유지, 실패 사진만 재시도 가능 |
| 실패 사진이 남은 채 반영 | 거부. 수기 보완 확인 후 가능 |
| 처리 중 영수증 삭제 | 도착한 결과를 버리고 오류 없이 종료 |
| 처리 중 서버 재시작 | 남은 사진이 `INTERRUPTED`로 바뀌고 재시도 가능 |
| 인식 후 이미지 추가·삭제 | 반영·재시도 409 `IMAGES_CHANGED` |
| 초안 생성 후 다른 사람이 영수증 수정 | 반영 409 `RECEIPT_CHANGED`, 확인 후 반영 가능 |
| 두 사람이 같은 초안을 저장 | 늦은 쪽 409 `DRAFT_REVISION_MISMATCH` |
| 반영 요청 두 번 | 품목이 한 번만 생성되고 두 번 모두 같은 상세 응답 |
| 수량 없음·범위 초과 값 | 초안 저장 가능, 반영 거부 |
| 총액 30,000원·품목 합계 33,000원 | 반영 가능, 정산은 `TOTAL_MISMATCH`로 차단 |
| 재인식 | 새 초안 생성, 이전 초안은 `DISCARDED`, 반영된 품목은 그대로 |
| 비멤버·공유 토큰으로 접근 | 거부 |
| 방 멤버 삭제 | 초안의 해당 참여자·결제자 정리, 삭제는 막지 않음 |

## 10. 공통 계약에 반영할 항목

- `receipts.image_list_version`, `receipts.content_version`, `items.ocr_draft_item_id` 컬럼과 의미
- OCR 작업·사진별 상태·초안 상태 enum 값
- 5장의 API 경로와 요청·응답 형식, HTTP 409 `CONFLICT`와 사유 코드
- 반영 조건과, 결제자·참여자·총액 누락이 반영을 막지 않는다는 점
- 품목이 없는 이미지 영수증은 반영 전까지 기존 규칙대로 방 정산을 `EMPTY_RECEIPT`로 차단한다는 점

## 11. 정해야 할 것

| 항목 | 현재 | 영향 |
|---|---|---|
| 품목이 이미 있는 영수증에 초안 반영 | 기존 품목을 초안 내용으로 전체 교체하고 화면에서 안내 | 기존 품목에 덧붙이는 방식이면 반영 로직과 중복 검사가 달라짐 |
| "모든 행 검토"의 판정 | 서버는 규칙으로 판정(`issues`가 없음). 행별 확인 체크는 저장하지 않음 | 행별 확인이 필요하면 초안 품목에 컬럼 추가 |
| 이미지가 바뀐 뒤의 초안 | 반영을 막고 새 인식을 요구 | 추가한 사진만 인식해 기존 초안에 붙이려면 작업 구조 변경 |
| 영수증당 인식 횟수 제한 | 없음 | 공급자 호출 비용 |
| 영수증 목록·상세의 OCR 상태 표시 | 응답에 포함하지 않음. 화면이 `GET /jobs/latest`로 조회 | 목록에서 "검토 필요"를 보여 주려면 요약 필드 추가 |
| 상태 조회와 요청 제한 | 2초 간격 조회, 제한은 IP당 60초 120회 그대로 | 같은 IP에서 여러 명이 동시에 인식하면 제한에 걸릴 수 있음. 조회 경로를 제한에서 빼거나 간격을 늘릴지 |
| 폐기된 초안의 보관 기간 | 영수증 삭제 시까지 | 정리 작업 필요 여부 |
| 공급자 응답 시간 초과 값 | 연결 3초, 응답 15초 | 실제 응답 시간 확인 후 조정 |
