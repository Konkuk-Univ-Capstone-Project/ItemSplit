# 이미지·OCR 상태 관리, DB 및 API 최종 설계

현재 구현된 PR 23 저장소(local/S3)·가짜 CLOVA 기반 인식 시작/조회/재시도 범위와 실행 방법은 [OCR 백엔드 개발 가이드](mock-backend.md)를 참고한다. 이 문서 전체가 구현된 것은 아니다.

영수증 이미지의 OCR 처리 상태, 검토 초안을 담을 테이블, 인식 시작부터 영수증 반영까지의 API와 트랜잭션 경계, 이미지 영수증의 수정 권한과 수정 요청을 정리한다. [week4 원본 보고서](../../report/backend-ocr-draft-design/week4-ocr-state-db-api-design.md)의 최종 결정을 기준으로 정리한 현재 설계다. DB 변경 절차는 PR #20의 Flyway 전환을 반영한다. OCR 정책·DB·API를 변경할 때는 이 문서와 관련 상세 명세를 함께 갱신한다. 설계 채택은 구현 완료를 뜻하지 않으며, 12장의 미결정 사항은 그대로 남는다.

[OCR 추출·수정 화면 요구사항](ocr-extraction-review-requirements.md)은 화면 동작을, [CLOVA OCR 연동 설계](clova-ocr-integration-design.md)는 공급자 호출·응답 매핑을 상세화한다. 이미지 저장은 [S3 저장 설계](../storage/image-storage-design.md)를 따른다. [공통 계약](../api-db-contract.md)의 금액 상한·멤버 ID·원자적 저장 규칙은 유지하되, 총액 입력 제거·OCR 총액 경고 정책은 공통 계약과 일치하며 수정 권한은 이 문서의 변경안을 우선한다. 공통 계약에 반영할 내용은 11장에 정리한다. 확정되지 않은 전제는 **[가정]** 으로 표시했다.

## 0. 결정 요약

| # | 항목 | 결정 | 다시 볼 조건 |
|---|---|---|---|
| 1 | OCR 처리 방식 | **비동기.** 인식 시작은 바로 응답하고, 서버 안의 작업 스레드가 사진을 한 장씩 처리한다. 화면은 상태 조회를 반복한다 | 서버를 2대 이상으로 늘릴 때 |
| 2 | 초안 저장 위치 | 정상 `items`와 분리한 OCR 전용 테이블 | — |
| 3 | 공급자 원문 응답 | 전체 응답은 저장하지 않는다. 변환한 후보와 필드별 원문·신뢰도·좌표만 보관한다 | 변환 오류 추적이 어려울 때 |
| 4 | 영수증과 인식의 관계 | 영수증 하나에 인식 작업 하나, 반영 한 번. 덮어쓰지 않는다. 다시 인식하려면 사진을 새 영수증으로 올린다 | 같은 영수증에서 다시 인식해야 할 때 |
| 5 | 이미지 고정 | 인식을 시작하면 그 영수증의 이미지는 추가·삭제할 수 없다 | — |
| 6 | 반영 방식 | 서버에 저장된 초안의 품목을 영수증에 **추가**한다. 기존 품목은 바뀌지 않는다 | — |
| 7 | 올린 사람 | 영수증을 만든 사용자를 기록한다. 이미지 추가·인식·초안 편집·반영과 인식에서 온 품목의 수정은 올린 사람만 한다 | 올린 사람이 방에 없는 경우를 다룰 때 |
| 8 | 수정 요청 | 다른 멤버는 인식에서 온 품목의 품목명·단가·수량·삭제를 요청하고 올린 사람이 승인한다. 수기 품목, 참여자 지정, 정산 제외, 영수증 이름·결제자·날짜는 누구나 고친다. 처리되지 않은 요청은 정산을 막지 않고 보류로 표시한다 | — |
| 9 | 품목 옮기기 | 수기 품목을 여러 개 골라 같은 방의 다른 영수증으로 옮긴다 | — |
| 10 | 중복 반영 방지 | 방 잠금 안에서 초안 상태를 `EDITING → APPLIED`로 바꾼다. `items.ocr_draft_item_id` 유일 제약을 안전망으로 둔다 | — |
| 11 | 검토 확인 | 행별 확인은 저장하지 않는다. 반영 요청에 "사진과 품목 내용을 확인했다"는 전체 확인이 있어야 한다 | — |
| 12 | 인식 호출 한도 | 사진당 최초 포함 누적 3회, 영수증당 하루 15회, 사용자당 하루 60회. 서비스 전체 상한은 설계만 유지 | 실제 사용량 확인 후 |
| 13 | 상태 조회 제한 | 일반 요청 제한과 분리해 인증 사용자당 60초에 60회 | — |
| 14 | 영수증 총액 | 사용자가 총액을 따로 입력하지 않고 품목 합계를 기준으로 한다. 인식한 총액은 초안 검토 때 품목 합계와 비교해 경고로만 보여 준다 | — |

## 1. 흐름과 구성

```
이미지 업로드(최대 3장) ─▶ 인식 시작 ─▶ 대기 ─▶ 처리 중 ─▶ 검토 가능 / 일부 실패 / 실패
     (올린 사람)          (이미지 고정)          │                    │
                                        사진별 공급자 호출        실패 사진 재시도
                                                 ▼                    ▼
                                       검토 초안(편집 중) ── 초안 저장(반복) ──▶ 영수증에 반영 ──▶ 반영 완료
                                                                                 └ items·assignments 추가
```

| 구성 | 책임 |
|---|---|
| `ocr.engine` | 이미지 한 장을 받아 공급자를 호출하고 내부 후보로 변환한다. DB와 권한을 알지 못한다 |
| `ocr` | 작업·사진별 상태, 초안 저장, 영수증 반영, 호출 한도 |
| `receipt` | 이미지 목록, 올린 사람, 정상 품목 저장 검증(반영 때 재사용), 수정 요청, 품목 옮기기 |
| `storage` | 저장된 이미지의 바이트 읽기 |

### 입력 순서

| 영수증 | 허용하는 순서 |
|---|---|
| 이미지로 만든 영수증 (`IMAGE_UPLOAD`) | 이미지 업로드 → 인식 → 초안 수정 → 반영 → 이후 수기로 품목 추가·수정. 인식 없이 수기로만 입력해도 된다 |
| 수기로 만든 영수증 (`MANUAL`) | 수기 입력만. 이미지 추가와 인식을 받지 않는다 |

- 영수증 하나가 모임의 한 차수(1차, 2차)다. 한 영수증에는 한 사람만 사진을 올린다.
- 이미지 영수증을 만들 때 결제자의 기본값은 올린 사람의 방 멤버다. 이후 누구나 바꿀 수 있다.
- 반영은 인식한 품목을 영수증에 추가한다. 반영 전에 넣어 둔 수기 품목은 그대로 남는다.

### 다시 올리기

사진을 잘못 찍었거나 인식 결과를 다시 받고 싶으면 같은 영수증을 고치지 않고 새로 올린다.

1. 사진을 새 영수증으로 올리고 인식·반영한다.
2. 이전 영수증에 남길 수기 품목이 있으면 골라서 새 영수증으로 옮긴다. (7장)
3. 이전 영수증을 삭제한다.

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
| `RECOGNITION_FAILED` | 응답은 받았으나 인식 실패 | 불가 (새 영수증으로 다시 올리기 또는 수기 보완) |
| `EMPTY_RESULT` | 품목이 하나도 없음 | 불가 (새 영수증으로 다시 올리기 또는 수기 보완) |
| `UNSUPPORTED_INPUT` | JPEG·PNG가 아님 | 불가 (새 영수증으로 다시 올리기) |

품목이 없는 결과는 성공으로 두지 않고 `EMPTY_RESULT` 실패로 기록한다. 자동 재시도는 하지 않는다.

### 초안 상태 `OcrDraftStatus`

| 값 | 의미 |
|---|---|
| `EDITING` | 편집 중. 저장·반영 가능 |
| `APPLIED` | 반영 완료. 더 이상 바뀌지 않음 |

### 수정 요청 상태 `ChangeRequestStatus`

`PENDING → APPROVED / REJECTED / CANCELED`. 승인·거절은 올린 사람이, 취소는 요청한 사람이 한다.

## 3. DB 변경안

enum은 기존 `ReceiptSourceType`처럼 문자열로 저장한다. 금액은 `BIGINT`, 수량은 `INTEGER`다.

### 3.1 기존 테이블에 추가하는 컬럼

| 테이블 | 컬럼 | 타입 | 용도 |
|---|---|---|---|
| `receipts` | `created_by_user_id` | `BIGINT NULL` FK | 영수증을 만든 사용자. 이미지 영수증에서는 사진을 올린 사람이다. 이 컬럼이 생기기 전의 영수증은 `NULL` |
| `items` | `ocr_draft_item_id` | `BIGINT NULL`, 유일 | 이 품목을 만든 초안 품목. 수기 품목은 `NULL` |

`items.ocr_draft_item_id`가 있는 품목을 이 문서에서 **인식 품목**, 없는 품목을 **수기 품목**이라 부른다.

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
| `receipt_id` | `BIGINT NOT NULL` FK, 유일 | 영수증 하나에 작업 하나 |
| `request_id` | `VARCHAR(64) NOT NULL` | 화면이 만든 식별자. 같은 인식 시작 요청의 재전송을 구분 |
| `status` | `VARCHAR(20) NOT NULL` | `OcrJobStatus` |
| `provider` | `VARCHAR(30) NOT NULL` | 사용한 공급자 (`CLOVA_RECEIPT` 등) |
| `requested_by_user_id` | `BIGINT NOT NULL` | 시작한 사용자 |
| `created_at`, `started_at`, `finished_at` | `TIMESTAMP` | |

### 3.4 `ocr_job_images` — 사진별 상태

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | |
| `job_id` | `BIGINT NOT NULL` FK | |
| `image_id` | `BIGINT NOT NULL` FK | `receipt_images.id`. 인식을 시작하면 이미지가 고정되므로 영수증 삭제 전까지 유지됨 |
| `sort_order` | `INTEGER NOT NULL` | 이미지 순서 |
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

작업 하나에 초안 하나다. 작업을 만들 때 함께 만든다. 초안은 품목만 담는다. 영수증 이름·결제자·날짜는 초안에 두지 않고 기존 영수증 수정 API로 바로 고친다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | `draftId` |
| `job_id` | `BIGINT NOT NULL` FK, 유일 | |
| `receipt_id` | `BIGINT NOT NULL` FK | |
| `status` | `VARCHAR(20) NOT NULL` | `OcrDraftStatus` |
| `revision` | `BIGINT NOT NULL DEFAULT 0` | 초안 저장마다 1 증가 (`@Version`) |
| `applied_at` | `TIMESTAMP` | |
| `applied_by_user_id` | `BIGINT` | 검토 확인을 하고 반영한 사용자 |
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
- 단가를 읽지 못했고 `행 금액 ÷ 수량`이 정수로 나누어떨어지면, 그 값을 `price`에 넣지 않고 응답의 `derivedUnitPrice`로만 알려 준다. 사용자가 **이 값 사용**을 눌러 채택해야 `price`가 된다. 화면은 현재 편집 중인 수량으로 계산 후보를 갱신하며, 나누어떨어지지 않거나 금액·수량 범위를 벗어나면 제시하지 않는다. 이미 입력한 단가는 덮어쓰지 않는다.
- `ocr_flags` 값: `DISCOUNT_OR_REFUND`(할인·환불 행), `NON_INTEGER_QUANTITY`(소수 수량), `UNPARSED_NUMBER`(숫자로 해석 못 함). 이런 행은 해석할 수 없는 편집값을 `NULL`로 두어 사용자가 고치거나 지우게 한다.
- 누락·범위 초과·금액 불일치 같은 검토 사유는 저장하지 않고, 조회할 때 현재 편집값으로 계산한다.

### 3.7 `item_change_requests` — 수정 요청

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT` PK | `requestId` |
| `receipt_id` | `BIGINT NOT NULL` FK | |
| `item_id` | `BIGINT NOT NULL` FK | 대상 인식 품목 |
| `type` | `VARCHAR(10) NOT NULL` | `UPDATE`, `DELETE` |
| `proposed_name` | `VARCHAR(255)` | `UPDATE`일 때 바꿀 품목명 |
| `proposed_price` | `BIGINT` | `UPDATE`일 때 바꿀 단가 |
| `proposed_quantity` | `INTEGER` | `UPDATE`일 때 바꿀 수량 |
| `comment` | `VARCHAR(200)` | 요청 사유 |
| `status` | `VARCHAR(10) NOT NULL` | `ChangeRequestStatus` |
| `requested_by_user_id` | `BIGINT NOT NULL` | |
| `decided_by_user_id` | `BIGINT` | |
| `created_at`, `decided_at` | `TIMESTAMP` | |

한 품목에 `PENDING` 요청은 하나만 둔다. 방 잠금 안에서 검사한다.

### 3.8 `ocr_daily_usage` — 일일 호출 수

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `quota_key` | `VARCHAR(80)` PK | `범위:대상ID:한국시간날짜`, 예: `RECEIPT:123:2026-10-12`, `USER:45:2026-10-12` |
| `used_count` | `INTEGER` | 그날 예약 또는 공급자에 보낸 사진 수. 호출 전 취소분 반환 |

DB에 영속화하며 영수증·작업 삭제 및 서버 재시작으로 사용량을 지우지 않는다. 영수증·사용자 외래 키의 삭제에 종속되지 않는다. `SERVICE` 범위는 향후 설계이며 현재 집계하지 않는다.

사진별 `ocr_job_images.quota_date`, `quota_reserved`, `quota_invoked`에 예약 날짜·예약 여부·공급자 호출 여부를 저장한다. 공급자 전달 직전에 호출 상태를 기록하며 그 이후 응답이 불확실한 서버 중단은 보수적으로 사용량에 남긴다. 호출 전 예약만 원래 날짜에 반환한다. 당일 한도 우회를 허용하는 사용량 삭제는 하지 않는다.

### 3.9 삭제와 정리

| 동작 | 처리 |
|---|---|
| 영수증·방 삭제 | 같은 트랜잭션에서 `item_change_requests → ocr_draft_item_members → ocr_draft_items → ocr_drafts → ocr_job_images → ocr_jobs → receipt_images` 순으로 삭제. 이미지 객체는 커밋 후 삭제 |
| 이미지 한 장 삭제 | 인식을 시작하기 전에만 가능. `receipt_images` 행 삭제, 객체는 커밋 후 삭제 |
| 품목 삭제 | 그 품목의 수정 요청도 함께 삭제 |
| 방 멤버 삭제 | 그 멤버의 `ocr_draft_item_members` 삭제. 연결된 사용자가 보낸 `PENDING` 수정 요청은 `CANCELED` |

### 3.10 기존 이미지 컬럼 이전

`receipts`의 `stored_path`, `original_filename`, `content_type`, `file_size`를 `receipt_images`로 옮긴다.

```sql
INSERT INTO receipt_images (receipt_id, sort_order, stored_path, original_filename, content_type, file_size, created_at)
SELECT r.id, 1, r.stored_path, r.original_filename, COALESCE(r.content_type, 'application/octet-stream'),
       COALESCE(r.file_size, 0), r.created_at
FROM receipts r
WHERE r.stored_path IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM receipt_images i WHERE i.receipt_id = r.id);
```

1. 다음 번호의 Flyway 스크립트(`src/main/resources/db/migration/V{번호}__{설명}.sql`)에서 새 테이블·컬럼을 만들고 위 복사를 수행한다. 이미 main에 반영된 V1·V2 등의 스크립트는 수정하지 않는다. 앱 시작 시 Flyway가 적용하고 Hibernate는 검증만 수행한다.
2. 코드는 `receipt_images`만 읽고 쓴다. `receipts`의 네 컬럼은 남겨 두되 사용하지 않는다.
3. 배포 후 문제가 없으면 새 버전의 Flyway 스크립트로 네 컬럼을 삭제한다. 실행·백업 절차는 [README의 DB 스키마 변경](../../README.md#db-스키마-변경)을 따른다.

이전에 올린 이미지의 `content_type`은 요청 헤더 값이라 실제 형식과 다를 수 있다. 인식 시작 때 형식을 다시 확인하고, JPEG·PNG가 아니면 그 사진만 `UNSUPPORTED_INPUT`으로 처리한다.

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
| ① 인식 시작 | 방 잠금 | 권한·이미지·기존 작업·호출 한도 확인, 작업·사진별 행·초안 생성. 커밋 후 작업 스레드에 넘김 |
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
    connect-timeout: 3s                 # 사진 한 장 호출 기준
    read-timeout: 15s
    stuck-after: 3m
    limit:
      per-receipt-daily: 15
      per-user-daily: 60
      # service-daily: 향후 설계, 현재 구현하지 않음
    # status-poll: 향후 설계, 현재 별도 제한을 구현하지 않음
    #   capacity: 60
    #   window-seconds: 60
    clova:
      invoke-url: ${CLOVA_OCR_INVOKE_URL:}
      secret: ${CLOVA_OCR_SECRET:}
```

연결 3초·응답 15초는 초기값이다. 실제 연동에서 소요 시간과 시간 초과 발생 여부를 확인해 조정한다.

`fake`는 정해진 합성 결과를 돌려주는 구현체로, 공급자 계정 없이 로컬 실행과 자동 테스트에 쓴다. `provider=clova`인데 주소나 키가 비어 있으면 앱 시작을 막는다. 운영 프로필에서는 `fake`로 시작하는 것도 막아, 설정 누락으로 가짜 결과가 나가지 않게 한다.

## 5. OCR API

모든 경로는 `/api/rooms/{roomId}/receipts/{receiptId}/ocr` 아래에 있고, JWT와 방 멤버 권한을 요구한다. 공유 토큰으로는 열지 않는다. 조회는 방 멤버 누구나, 그 외는 올린 사람만 할 수 있다. (6.1)

| 메서드·경로 | 동작 | 권한 | 응답 |
|---|---|---|---|
| `POST /jobs` | 인식 시작 | 올린 사람 | 202, 작업 응답 |
| `GET /jobs/latest` | 이 영수증의 작업과 초안. 없으면 `data: null` | 멤버 | 200 |
| `GET /jobs/{jobId}` | 작업 상태와 초안 (상태 확인용 반복 조회) | 멤버 | 200 |
| `POST /jobs/{jobId}/images/{imageId}/retry` | 실패한 사진 한 장 재시도 | 올린 사람 | 202, 작업 응답 |
| `PUT /jobs/{jobId}/draft` | 초안 저장 | 올린 사람 | 200, 작업 응답 |
| `POST /jobs/{jobId}/draft/apply` | 영수증에 반영 | 올린 사람 | 200, 기존 영수증 상세 응답 |

### 5.1 인식 시작

요청 `{ "requestId": "8d3f…" }`. 화면이 UUID를 만들고 같은 요청의 재전송에 다시 쓴다.

- 이미지가 한 장도 없거나 `MANUAL` 영수증이면 400.
- 같은 `requestId`가 다시 오면 기존 작업을 그대로 돌려준다.
- 이 영수증에 이미 작업이 있고 `requestId`가 다르면 409 `JOB_ALREADY_EXISTS`. 다시 인식하려면 새 영수증으로 올린다.
- 작업이 만들어지면 이 영수증의 이미지 추가·삭제는 409 `IMAGES_LOCKED`가 된다.
- 영수증에 수기 품목이 있어도 시작할 수 있다. 기존 품목은 바뀌지 않는다.
- 호출 한도를 넘으면 429. (5.8)

### 5.2 작업 응답

```json
{
  "jobId": 7,
  "status": "PARTIALLY_FAILED",
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
    "blockers": ["IMAGE_FAILED_UNCOVERED", "ITEM_ISSUES"],
    "warnings": ["OCR_TOTAL_MISMATCH"]
  }
}
```

- `candidates`의 상호명·날짜·총액은 참고 정보다. 화면은 이를 보여 주고, 사용자가 채택하면 기존 영수증 수정 API로 이름·날짜를 저장한다.
- `issues`(반영을 막음): `MISSING_NAME`, `MISSING_PRICE`, `MISSING_QUANTITY`, `PRICE_OUT_OF_RANGE`, `QUANTITY_OUT_OF_RANGE`, `LINE_AMOUNT_OUT_OF_RANGE`, `DUPLICATE_UNRESOLVED`, `EXCLUDED_WITH_MEMBERS`.
- `warnings`(반영을 막지 않음): `LINE_AMOUNT_MISMATCH`(단가 × 수량이 인식한 행 금액과 다름), `SPECIAL_LINE`(할인·환불·소수 수량 표시가 있는 행).
- `blockers`(초안 단위): `JOB_IN_PROGRESS`, `IMAGE_FAILED_UNCOVERED`, `NO_ITEMS`, `ITEM_ISSUES`, `RECEIPT_TOTAL_LIMIT`.
- `itemsTotal`은 삭제하지 않은 행 중 단가·수량이 모두 있는 행의 합계다. 제외 품목도 포함한다.
- 초안 단위 `warnings`(반영을 막지 않음): `OCR_TOTAL_MISMATCH`. 사진에서 읽은 총액 후보가 있는데 그중 어느 것도 `itemsTotal`과 같지 않을 때 붙는다. 품목 누락·오인식, 수량·가격·할인 인식 오류 또는 사용자 초안 편집을 확인하라는 뜻이며, 총액 후보가 없으면 붙지 않는다. 제외 품목은 합계에 포함되므로 제외만으로 차이가 생기지 않고 삭제한 행은 합계에서 빠진다.
- 중복 의심 쌍은 한 행을 삭제하거나 양쪽에 `duplicateConfirmed = true`를 저장하면 해결된다.
- 상태 조회 방법은 5.9를 따른다.

### 5.3 실패 사진 재시도

- `FAILED`이고 재시도 가능한 오류일 때만 받는다. 성공한 사진은 다시 호출하지 않는다.
- 검토 화면에서 업로더에게 실패 사진별 **다시 인식** 버튼을 제공한다. 미저장 변경이 있으면 먼저 검토 내용을 저장해야 한다. 재시도는 성공 결과와 저장한 편집 내용을 보존하며 수기 보완 품목도 자동 삭제하지 않는다. 한도 초과·재시도 불가 사유를 표시하고 수기 보완을 안내한다.
- 사진별 누적 최대 3회는 최초 시도 1회와 재시도 최대 2회를 합친 값이다. 날짜가 바뀌어도 초기화하지 않으며 소진 시 429 `OCR_ATTEMPT_LIMIT`이다.
- 그 사진이 이미 `PENDING`·`PROCESSING`이면 현재 상태를 그대로 돌려준다(중복 클릭).
- 재시도 결과는 같은 초안에 그 사진의 품목으로 추가된다. 기존 편집값은 바뀌지 않는다.
- 재시도도 호출 한도에 포함한다. (5.8)

### 5.4 초안 저장

```json
{
  "revision": 2,
  "coveredImageIds": [42],
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
- 참여자 ID는 저장 시점에 검사한다. 해당 방의 `RoomMember.id`가 아니면 400이다.
- 인식한 값(`ocr_*`)과 출처는 이 요청으로 바뀌지 않는다.
- `coveredImageIds`는 실패한 사진 중 수기로 보완했다고 확인한 사진이다.
- `revision`이 현재 값과 다르면 409 `DRAFT_REVISION_MISMATCH`. 같은 사람이 두 기기에서 편집한 경우다.
- 작업이 진행 중이면 409 `JOB_IN_PROGRESS`, 초안이 `EDITING`이 아니면 409 `DRAFT_NOT_EDITABLE`.

### 5.5 영수증에 반영

요청 `{ "revision": 3, "reviewConfirmed": true }`. 반영하는 내용은 서버에 저장된 초안이다.

`reviewConfirmed`는 화면에서 "사진과 품목 내용을 확인했습니다"를 확인받았다는 표시다. `true`가 아니면 400이다. 확인은 요청의 `revision`에 묶이므로, 확인 뒤에 내용을 고쳐 저장하면 `revision`이 바뀌고 화면은 확인을 해제해 다시 받는다. 서버는 확인 상태를 따로 저장하지 않고 반영한 사용자만 기록한다.

반영 조건:

1. 초안이 `EDITING`이고 `revision`이 일치한다.
2. 작업이 진행 중이 아니다.
3. 실패한 사진이 없거나 모두 `manually_covered`다.
4. 삭제하지 않은 행이 하나 이상이고 `issues`가 없다.
5. 기존 품목과 합친 금액이 공통 계약의 상한(품목·영수증·방 합계)을 통과한다.

참여자 미지정과 `OCR_TOTAL_MISMATCH` 경고는 반영을 막지 않는다. 참여자가 없는 품목은 공통 계약대로 저장은 되고 정산만 차단된다.

처리 순서(하나의 트랜잭션):

1. 방 잠금, 멤버·올린 사람 확인.
2. 초안이 이미 `APPLIED`이면 변경 없이 현재 영수증 상세를 돌려준다. 요청의 `revision`이 반영된 값과 다르면 409.
3. 위 조건 검사.
4. 삭제하지 않은 초안 품목을 기존 품목 추가와 같은 금액·멤버 검증을 거쳐 품목과 배정으로 만든다. 새 품목에 `ocr_draft_item_id`를 기록한다. 영수증의 기존 품목은 건드리지 않는다.
5. 초안 `APPLIED`, `applied_at`·`applied_by_user_id` 기록.

반영 버튼을 여러 번 누르거나 응답을 받지 못해 다시 보내도 2번에서 걸러져 품목이 늘지 않는다.

### 5.6 충돌 응답

`ErrorCode`에 `CONFLICT`(HTTP 409)를 추가한다. 화면이 구분할 수 있도록 사유를 `error.details[].reason`에 담는다.

| 사유 | 상황 | 화면 안내 |
|---|---|---|
| `JOB_IN_PROGRESS` | 처리 중인 작업이 있음 | 완료까지 대기 |
| `JOB_ALREADY_EXISTS` | 이미 인식한 영수증에서 다시 인식 시작 | 새 영수증으로 올리기 |
| `IMAGES_LOCKED` | 인식을 시작한 영수증에 이미지 추가·삭제 | 새 영수증으로 올리기 |
| `DRAFT_REVISION_MISMATCH` | 다른 기기에서 초안을 먼저 저장함 | 다시 불러오기 |
| `DRAFT_NOT_EDITABLE` | 반영 완료된 초안 | 영수증 화면으로 이동 |
| `CHANGE_REQUEST_PENDING` | 그 품목에 대기 중인 수정 요청이 이미 있음 | 기존 요청 보여 주기 |

### 5.7 영수증 목록·상세의 OCR 요약

`GET /api/rooms/{roomId}/receipts`와 `GET .../receipts/{receiptId}` 응답에 아래 필드를 넣는다. 작업이 없으면 `ocr`은 `null`이다.

```json
"createdByUserId": 3,
"ocr": { "jobId": 7, "jobStatus": "REVIEWABLE", "draftStatus": "EDITING" },
"pendingChangeRequestCount": 1
```

| 화면 표시 | 조건 (위에서부터 먼저 맞는 것) |
|---|---|
| 반영 완료 | `draftStatus = APPLIED` |
| 인식 중 | `jobStatus`가 `PENDING`·`PROCESSING` |
| 실패 | `jobStatus = FAILED` |
| 일부 실패 | `jobStatus = PARTIALLY_FAILED` |
| 검토 필요 | `jobStatus = REVIEWABLE` |

- 목록은 방의 영수증 전체에 대해 작업과 대기 요청 수를 영수증 수와 관계없는 쿼리로 함께 읽는다. 화면이 영수증마다 `GET /jobs/latest`를 호출하지 않는다.
- 사진별 상태와 초안 내용은 요약에 넣지 않는다. 상세 결과는 `GET /jobs/{jobId}`로 읽는다.
- 영수증 상세의 품목마다 `ocrOrigin`(인식 품목 여부)을 넣어, 화면이 직접 수정과 수정 요청을 구분하게 한다.
- "반영 완료"는 "정산 가능"과 다르다. 정산 가능 여부는 기존 정산 응답의 `ready`·`issues`로, 정산 보류는 `holds`로 따로 표시한다. (6.3)

### 5.8 인식 호출 한도

| 범위 | 초기값 | 설정 |
|---|---|---|
| 영수증당 하루 | 15회 | `app.ocr.limit.per-receipt-daily` |
| 사용자당 하루 | 60회 | `app.ocr.limit.per-user-daily` |
| 서비스 전체 하루 | 설계만 유지, 미구현 | 향후 `app.ocr.limit.service-daily` |

- 사진 한 장을 공급자에 보내는 것이 1회다. 사진 3장을 인식하면 3회이고, 실패 사진 재시도도 1회로 센다.
- 사용자 한도는 인식 시작이나 재시도를 요청한 사용자에게 센다. 날짜는 한국 시간 자정에 바뀐다.
- 같은 `requestId`가 다시 와서 기존 작업을 돌려주는 경우는 세지 않는다.
- 인식 시작·재시도 요청을 받을 때 전체 사진의 필요 횟수를 원자적으로 예약하고, 실제 공급자를 호출하지 못한 사진은 예약했던 날짜의 횟수를 반환한다. `UNSUPPORTED_INPUT`도 공급자 호출 뒤 응답으로 받은 경우에는 사용량에 포함한다.
- 남은 횟수가 요청한 사진 수보다 적으면 일부만 처리하지 않고 요청 전체를 거부한다.
- 여러 방의 요청이 같은 사용자 행을 동시에 올릴 수 있으므로, `INSERT ... ON CONFLICT DO NOTHING`으로 행을 준비하고 한도 비교와 증가를 조건부 `UPDATE`로 처리한다. 영수증·사용자 예약은 같은 트랜잭션으로 묶어 한쪽이라도 부족하면 모두 롤백한다.
- 초과하면 HTTP 429 `TOO_MANY_REQUESTS`이고, 사유는 `error.details[].reason`에 `OCR_RECEIPT_DAILY_LIMIT`, `OCR_USER_DAILY_LIMIT` 중 하나로 담는다. 서비스 전체 한도와 `OCR_SERVICE_DAILY_LIMIT`은 향후 설계다.
- 대기·처리 중 재시도 중복 요청도 추가 차감하지 않는다. DB 카운터는 영수증·작업 삭제나 재시작으로 초기화하지 않는다.
- 사진별 최초 포함 누적 최대 3회와 최대 3장 정책에 따라 영수증의 최대 누적 시도는 9회다. 일일 15회는 현재 기본 흐름에서는 도달하지 않는 추가 안전장치다. 테스트는 일일 한도를 낮추거나 사용량 예약 계층을 직접 호출한다.

### 5.9 상태 조회 제한 (서버 전용 제한은 미구현, 화면 조회 정책은 구현)

`GET /jobs/{jobId}`와 `GET /jobs/latest`는 일반 요청 제한(IP당 60초에 120회)에서 빼고, 인증 사용자당 60초에 60회로 따로 센다. 초과하면 기존과 같은 429와 `Retry-After`를 돌려준다.

화면 쪽 규칙:

- 작업이 `PENDING`·`PROCESSING`인 동안만 조회한다. 처음 10초는 2초 간격, 이후는 5초 간격이다.
- 작업이 끝나거나 화면을 벗어나면 멈춘다.
- 이전 조회의 응답이 오기 전에 다음 요청을 보내지 않는다.
- 429는 `Retry-After`의 초 또는 HTTP 날짜를 우선 적용하고, 없으면 5→10→20→30초로 조회 간격을 늘린다. 네트워크·5xx 오류는 5→10→20초로 최대 3회 재조회하고 계속 실패하면 수동 확인을 안내한다. 401·403·404 등 복구할 수 없는 응답은 즉시 중단한다. 화면 이탈 시 진행 중 요청도 취소한다.
- 영수증 목록에서는 작업별로 반복 조회하지 않고 목록 응답의 요약을 쓴다.

## 6. 수정 권한과 수정 요청

### 6.1 권한

이미지 영수증 가운데 올린 사람 기록(`created_by_user_id`)이 있는 영수증에 적용한다. 수기 영수증과 올린 사람 기록이 없는 기존 영수증은 지금처럼 방 멤버 누구나 고친다.

| 동작 | 올린 사람 | 다른 멤버 |
|---|---|---|
| 이미지 추가·삭제 (인식 시작 전) | 가능 | 403 |
| 인식 시작, 실패 사진 재시도, 초안 저장, 반영 | 가능 | 403 |
| 작업·초안 조회, 이미지 조회 | 가능 | 가능 |
| 인식 품목의 품목명·단가·수량 수정, 삭제 | 가능 | 수정 요청 |
| 수기 품목 추가·수정·삭제 | 가능 | 가능 |
| 품목 참여자 지정, 정산 제외 (인식 품목 포함) | 가능 | 가능 |
| 영수증 이름·결제자·날짜 수정 | 가능 | 가능 |
| 수기 품목 옮기기 | 가능 | 가능 |
| 수정 요청 승인·거절 | 가능 | 403 |

기존 API에서 달라지는 점:

- 개별 품목 수정·삭제: 대상이 인식 품목이고 요청자가 올린 사람이 아니면 403.
- 영수증 전체 저장(`/contents`): 요청자가 올린 사람이 아니면, 인식 품목은 목록에 모두 남아 있어야 하고 품목명·단가·수량이 현재 값과 같아야 한다. 참여자와 정산 제외는 바꿀 수 있다. 어기면 403이고 아무것도 저장하지 않는다.
- 참여자 지정 API는 그대로 누구나 쓴다.

### 6.2 수정 요청 API

경로는 `/api/rooms/{roomId}/receipts/{receiptId}/change-requests` 아래다.

| 메서드·경로 | 동작 | 권한 |
|---|---|---|
| `POST /` | 수정 요청 보내기 | 올린 사람이 아닌 멤버 |
| `GET /` | 이 영수증의 요청 목록 (`status`로 거름) | 멤버 |
| `POST /{requestId}/approve` | 승인하고 품목에 반영 | 올린 사람 |
| `POST /{requestId}/reject` | 거절 | 올린 사람 |
| `POST /{requestId}/cancel` | 취소 | 요청한 사람 |
| `GET /api/rooms/{roomId}/change-requests?status=PENDING` | 방 전체의 요청 목록. 내가 승인할 요청과 내가 보낸 요청을 구분해 돌려준다 | 멤버 |

요청 본문:

```json
{ "itemId": 301, "type": "UPDATE", "name": "된장찌개", "price": 9000, "quantity": 2, "comment": "가격이 9,000원이었음" }
```

- 대상은 이 영수증의 인식 품목이어야 한다. 수기 품목이면 400이다(직접 고치면 된다).
- `UPDATE`는 바꾼 뒤의 품목명·단가·수량 전체를 보낸다. `DELETE`는 세 값을 보내지 않는다.
- 제안한 값은 요청할 때와 승인할 때 모두 공통 계약의 금액 범위와 합계 상한을 검사한다.
- 그 품목에 `PENDING` 요청이 이미 있으면 409 `CHANGE_REQUEST_PENDING`.
- 승인하면 같은 트랜잭션에서 품목을 수정하거나 삭제하고 요청을 `APPROVED`로 바꾼다. 수정은 기존 품목 수정과 같이 참여자와 정산 제외를 보존한다.
- 올린 사람이 그 품목을 직접 수정하면 그 품목의 `PENDING` 요청은 `REJECTED`로 닫는다. 요청이 가리키던 값이 달라졌기 때문이다.

### 6.3 정산과의 관계

`PENDING` 수정 요청은 정산을 막지 않는다. 정산 결과는 현재 입력된 품목으로 그대로 계산해 보여 주고, 화면이 "정산 보류"를 표시할 수 있도록 정산 응답에 `holds`를 추가한다.

```json
"holds": [
  { "code": "PENDING_CHANGE_REQUEST", "receiptId": 21, "itemId": 301, "requestId": 9 }
]
```

- `holds`는 `ready`와 `members` 계산에 영향을 주지 않는다. 정산을 막는 사유는 기존처럼 `issues`에만 담는다.
- 요청이 승인·거절·취소되면 `holds`에서 빠진다. 없으면 빈 배열이다.
- 공유 토큰으로 보는 정산 응답에도 `holds`를 넣어 같은 표시를 하게 한다. 공유 토큰 발급은 `holds`로 막지 않는다.

## 7. 품목 옮기기

`POST /api/rooms/{roomId}/receipts/{receiptId}/items/move`

```json
{ "targetReceiptId": 31, "itemIds": [201, 202, 205] }
```

- 방 멤버 누구나 쓴다. 두 영수증은 같은 방에 있어야 한다.
- `itemIds`는 모두 원래 영수증의 수기 품목이어야 한다. 인식 품목이 섞여 있으면 400이고 아무것도 옮기지 않는다.
- 품목의 참여자 배정과 정산 제외 상태는 그대로 따라간다.
- 옮긴 뒤 대상 영수증의 품목 합계가 영수증 상한을 넘으면 400이다. 같은 방 안의 이동이므로 방 합계는 변하지 않는다.
- 방 잠금 안의 한 트랜잭션으로 처리하고, 대상 영수증의 상세 응답을 돌려준다.
- 원래 영수증에 품목이 하나도 남지 않으면 삭제하기 전까지 기존 규칙대로 정산에서 `EMPTY_RECEIPT`로 표시된다.

## 8. 내부 인터페이스

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

## 9. 기존 코드에서 바뀌는 곳

| 위치 | 변경 |
|---|---|
| `Receipt` | `createdByUserId` 추가. 이미지 컬럼 4개는 사용 중단 |
| 이미지 영수증 생성 | 올린 사람 기록, 결제자 기본값을 올린 사람의 방 멤버로 |
| 이미지 추가·삭제 API | `MANUAL` 영수증이면 거부, 올린 사람만 허용, 인식 시작 후에는 409 |
| `ItemService`의 품목 수정·삭제 | 인식 품목은 올린 사람만. 직접 수정 시 그 품목의 대기 요청 닫기 |
| `ReceiptService.saveContents` | 올린 사람이 아닌 요청자의 인식 품목 변경 거부 |
| `Item` | `ocrDraftItemId` 추가 |
| `SettlementService` | 응답에 `holds` 추가(정산은 막지 않음). 총액 관련 차단 코드 `MISSING_DECLARED_TOTAL`·`TOTAL_MISMATCH`·`INVALID_DECLARED_TOTAL` 제거 |
| 영수증 생성·수정 요청과 응답 | `declaredTotal` 입력과 영수증 상세의 총액 불일치 `warning` 제거. `receipts.declared_total` 컬럼은 남겨 두되 사용하지 않음 |
| `ReceiptService.deleteReceipt`, `RoomService.deleteRoom` | OCR 행·수정 요청·`receipt_images` 삭제, 이미지 객체 정리 대상을 `receipt_images`에서 가져옴 |
| `RoomService.deleteMember` | 초안의 참여자 정리, 그 사용자의 대기 요청 취소 |
| 영수증 목록·상세 응답 | `createdByUserId`, `ocr` 요약, `pendingChangeRequestCount`, 품목의 `ocrOrigin` 추가 |
| `RateLimitFilter` | 상태 조회 경로를 IP 기준 집계에서 제외. 이 필터는 인증보다 먼저 실행되어 사용자를 알 수 없으므로, 사용자 기준 제한은 인증 뒤에 실행되는 별도 구성으로 둔다 |
| `ErrorCode` | `CONFLICT` 추가 |
| `StorageService`와 구현체 | `read` 추가 |

## 10. 확인할 동작

| 상황 | 기대 결과 |
|---|---|
| 같은 `requestId`로 인식 시작 두 번 | 작업 하나, 공급자 호출도 한 번씩 |
| 인식한 영수증에서 다른 `requestId`로 인식 시작 | 409 `JOB_ALREADY_EXISTS` |
| 인식을 시작한 영수증에 이미지 추가·삭제 | 409 `IMAGES_LOCKED` |
| `MANUAL` 영수증에 이미지 추가·인식 시작 | 400 |
| 올린 사람이 아닌 멤버가 이미지 추가·인식 시작·초안 저장·반영 | 403 |
| 3장 중 1장 실패 | `PARTIALLY_FAILED`, 성공한 사진의 품목 유지, 실패 사진만 재시도 가능 |
| 실패 사진이 남은 채 반영 | 거부. 수기 보완 확인 후 가능 |
| 처리 중 영수증 삭제 | 도착한 결과를 버리고 오류 없이 종료 |
| 처리 중 서버 재시작 | 남은 사진이 `INTERRUPTED`로 바뀌고 재시도 가능 |
| 수기 품목이 있는 영수증에 반영 | 수기 품목은 그대로 있고 인식 품목이 추가됨 |
| 반영 요청 두 번 | 품목이 한 번만 생성되고 두 번 모두 같은 상세 응답 |
| `reviewConfirmed` 없이 반영 | 400 |
| 수량 없음·범위 초과 값 | 초안 저장 가능, 반영 거부 |
| 다른 멤버가 인식 품목의 단가를 직접 수정 | 403, 값 변화 없음 |
| 다른 멤버가 인식 품목의 참여자 지정·정산 제외 | 저장됨 |
| 다른 멤버가 수기 품목 수정·삭제 | 저장됨 |
| 다른 멤버가 `/contents`로 인식 품목을 빼거나 값을 바꿔 저장 | 403, 아무것도 저장되지 않음 |
| 수정 요청 승인 | 품목이 제안한 값으로 바뀌고 참여자는 그대로 |
| 삭제 요청 승인 | 품목과 배정 삭제 |
| 대기 중인 요청이 있는 품목에 또 요청 | 409 `CHANGE_REQUEST_PENDING` |
| 올린 사람이 대기 요청이 있는 품목을 직접 수정 | 요청이 `REJECTED`로 닫힘 |
| 대기 중인 요청이 있는 방의 정산 조회 | 정산 결과는 그대로 계산되고 `holds`에 `PENDING_CHANGE_REQUEST` |
| 요청을 승인·거절·취소한 뒤 정산 조회 | `holds`가 비어 있음 |
| 인식한 총액 30,000원·초안 품목 합계 27,000원 | 초안에 `OCR_TOTAL_MISMATCH` 경고, 반영은 가능 |
| 총액 없이 영수증 생성 후 정산 조회 | 총액 때문에 차단되지 않음 |
| 수기 품목 3개를 다른 영수증으로 옮기기 | 품목과 참여자가 대상 영수증으로 이동, 원래 영수증에서 사라짐 |
| 옮길 품목에 인식 품목이 섞임 | 400, 아무것도 옮기지 않음 |
| 옮긴 뒤 대상 영수증 합계가 상한 초과 | 400 |
| 영수증 일일 한도를 낮춘 테스트 또는 사용량 예약 계층에서 한도 초과 | 429 `OCR_RECEIPT_DAILY_LIMIT`. 기본 3장 × 3회 흐름에서는 15회 도달 불가 |
| 사진 최초 시도와 재시도 합계 3회 후 다음 날 다시 시도 | 429 `OCR_ATTEMPT_LIMIT`, 날짜 변경으로 초기화하지 않음 |
| 사용자 일일 60회 사용 후 새 사진 요청 | 429 `OCR_USER_DAILY_LIMIT` |
| 영수증 삭제·서버 재시작 후 사용자 사용량 조회 | 기존 사용량 유지 |
| 남은 횟수 2회에서 사진 3장 인식 시작 | 요청 전체 거부, 횟수 변화 없음 |
| 저장소에서 읽지 못한 사진 | 호출 횟수에 포함되지 않음 |
| 별도 상태 조회 제한 구현 후 60초에 61번 | 429. 같은 IP의 다른 API 한도는 줄지 않음 |
| 영수증 목록 조회 | 영수증 수와 관계없이 OCR 요약 쿼리 수가 일정 |
| 비멤버·공유 토큰으로 OCR·수정 요청 접근 | 거부 |

## 11. 공통 계약에 반영할 항목

- `receipts.created_by_user_id`, `items.ocr_draft_item_id` 컬럼과 의미, 인식 품목과 수기 품목의 구분
- OCR 작업·사진별 상태·초안 상태·수정 요청 상태 enum 값
- 5·6·7장의 API 경로와 요청·응답 형식, HTTP 409 `CONFLICT`와 사유 코드
- 수정 권한: 기존의 "방 멤버 누구나 수정"이 이미지 영수증의 인식 품목에 대해서는 올린 사람과 수정 요청으로 바뀐다는 점
- 입력 순서: 이미지 추가·인식은 `IMAGE_UPLOAD` 영수증만, 영수증 하나에 인식 한 번, 반영은 품목 추가
- 정산 응답의 `holds`와 `PENDING_CHANGE_REQUEST`. 정산을 막지 않는 표시라는 점
- 영수증 총액: 2·3·5장의 총액 입력과 불일치 차단 규칙을 없애고 품목 합계를 기준으로 한다는 점. 차단 코드 `MISSING_DECLARED_TOTAL`·`TOTAL_MISMATCH`·`INVALID_DECLARED_TOTAL` 제거
- 영수증 목록·상세의 추가 필드와 화면 표시 구분
- 인식 호출 한도와 상태 조회 제한
- 품목이 없는 이미지 영수증은 반영 전까지 기존 규칙대로 방 정산을 `EMPTY_RECEIPT`로 차단한다는 점

## 12. 정해야 할 것

| 항목 | 현재 | 영향 |
|---|---|---|
| 올린 사람이 방에 없을 때 | 정하지 않음. 올린 사람만 인식 품목을 고치고 승인할 수 있음 | 올린 사람이 방을 나가거나 응답하지 않으면 인식 품목 수정과 대기 요청 처리가 멈춤. 정산은 보류 표시만 되고 계산은 계속됨 |
| 검토 중 잠금 | 없음. 초안에 영수증 이름·결제자·날짜를 담지 않아, 검토 중에 다른 멤버가 영수증을 고쳐도 반영이 덮어쓰지 않음 | 인식 시작부터 반영까지 올린 사람만 수정하게 잠그면 이름·결제자·날짜를 초안에 담아 한 번에 저장할 수 있고, 반영 전에 다른 멤버가 넣은 수기 품목이 인식 결과와 중복되는 것도 막음. 인식이 모두 실패했을 때 잠금을 푸는 "인식 포기" 동작이 함께 필요 |
| 영수증 삭제 권한 | 기존대로 방 멤버 누구나 | 올린 사람만 삭제하게 할지 |
| 서비스 전체 일일 호출 상한 값 | 설계만 유지, 미구현 | 공급자 요금제와 예산에 맞춰 운영 전에 설정 |
