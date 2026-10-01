# S3 저장 구조·접근 권한·운영 환경

S3 이미지 저장의 업로드 경로, 조회 방식, 삭제 재시도, 버킷 구성, IAM 권한, 애플리케이션 설정, 테스트, 운영 환경을 정리한다. 기준 코드는 `main` 4671a0c이며, 이미지 형식·최대 크기·영수증당 장수는 요구사항 문서의 값(JPEG/PNG, 장당 10MB, 영수증당 최대 3장)을 따른다. 확정되지 않은 전제는 **[가정]** 으로 표시했다.

## 0. 결정 요약

| # | 항목 | 결정 | 다시 볼 조건 |
|---|---|---|---|
| 1 | 업로드 경로 | **서버 경유** (지금처럼 multipart → 백엔드 → S3) | 10MB보다 큰 원본이 필요해질 때 |
| 2 | 로컬 저장소 조회 | **백엔드가 서명한 단기 URL** (S3 presigned와 같은 응답 형식) | — |
| 3 | 삭제 재시도 큐 | 우선 기존 파일 큐 + 볼륨 유지 → 이후 **DB 테이블(outbox)** 로 이전 | — |
| 4 | 버킷 구성 | **환경별 버킷** (운영 1개, 필요하면 개발 1개). 로컬 개발은 S3를 쓰지 않음 | — |
| 5 | 이미지 메타데이터 위치 | **별도 테이블 `receipt_images`** (영수증 ID, 순서, 객체 키, 원본 파일명, 형식, 크기). `receipts`의 이미지 컬럼은 이 테이블로 옮김 | — |
| 6 | 보관 기간 | 영수증·방 삭제 시까지 보관 | 정산 완료 기능이 생길 때 |

## 1. 업로드 경로 — 서버 경유

```
브라우저 ──multipart(1장)──▶ nginx ──▶ Spring (권한·형식·크기·장수 검사) ──PutObject──▶ S3
                                          └─ DB에 receipt_images 행 저장 (같은 트랜잭션, 롤백 시 객체 삭제)
```

여러 장은 한 요청에 묶지 않고 한 장씩 올린다. 요청 크기 제한을 장당 10MB 그대로 쓸 수 있고, 한 장이 실패해도 나머지에 영향이 없다. 장수 검사(최대 3장)는 같은 영수증에 동시에 올리는 경우를 막기 위해 영수증 행 잠금 안에서 한다.

| 비교 | 서버 경유 (채택) | presigned PUT (브라우저 → S3 직접) |
|---|---|---|
| 형식 검사(매직 넘버) | 서버가 바로 검사 | 업로드 후 별도 검사 단계 필요 |
| 고아 객체 | 롤백 시 바로 삭제 (기존 `deleteAfterRollback`) | URL만 받고 확정하지 않으면 객체가 남음 |
| 흐름 | 요청 1번 | URL 발급 → 업로드 → 확정, 요청 3번 |
| 서버 부하 | 10MB까지는 문제 없음 | 서버를 거치지 않음 |
| OCR 연동 [가정: 서버가 OCR 호출] | 서버가 이미 바이트를 갖고 있음. URL 방식 OCR이면 presigned GET을 넘기면 됨 | 서버가 S3에서 다시 읽어야 함 |

OCR 서비스가 파일·URL 중 무엇을 받든 둘 다 지원할 수 있다. 현재 `StorageService.store(directory, MultipartFile)` 시그니처를 그대로 쓴다.

## 2. 조회 — 저장소와 상관없이 같은 응답

`GET /api/rooms/{roomId}/receipts/{receiptId}/images` (방 멤버만, JWT 필요)

```json
[
  { "imageId": 41, "order": 1, "url": "https://...", "expiresAt": "2026-10-01T12:05:00Z", "contentType": "image/jpeg" },
  { "imageId": 42, "order": 2, "url": "https://...", "expiresAt": "2026-10-01T12:05:00Z", "contentType": "image/jpeg" }
]
```

화면은 각 `url`을 `<img src>`에 그대로 넣는다. `<img>`는 `Authorization` 헤더를 보내지 못하므로, 권한 확인은 이 API에서 하고 `url` 자체는 짧은 시간 유효한 서명으로 보호한다.

| 저장소 | `url` 생성 방법 |
|---|---|
| S3 | `S3Presigner`로 presigned GET URL, 유효 5분. S3 호출 없이 서명만 계산 |
| 로컬 | `/api/storage/local/{key}?expires=...&sig=...` — 키와 만료 시각을 HMAC-SHA256으로 서명. 이 경로는 JWT 대신 서명을 검사하고 파일을 스트리밍 |

인터페이스 추가안:

```java
public interface StorageService {
    StoredFile store(String directory, MultipartFile file) throws IOException;
    void delete(String storedPath) throws IOException;
    SignedUrl createReadUrl(String storedPath, Duration ttl);   // 추가
    record SignedUrl(String url, Instant expiresAt) { }
}
```

- 공유 토큰(`/api/shared/...`)으로는 이 API를 열지 않는다. (계약 문서 §4)
- 로컬 서명 키는 JWT 키와 따로 둔다: `app.storage.local.url-signing-secret`.
- presigned URL은 서명이 포함되어 있으므로 로그에 남기지 않는다.
- 이미지가 없는 영수증(수기 입력)은 빈 배열.
- 최대 3장이므로 목록 조회 한 번에 서명 3개까지 계산한다. S3 호출은 없다.

## 3. 삭제 재시도 큐

현재(PR #16): 실패한 삭제를 `{storage root}/.cleanup-pending/*.pending` 파일로 남기고 60초마다 재시도한다.

S3로 바꾸면 생기는 문제:
- 저장 루트가 S3라서 큐를 둘 로컬 위치가 따로 필요하다. 컨테이너를 다시 만들면 볼륨 없이는 큐가 사라진다.
- 커밋 직후 프로세스가 죽으면 삭제 기록 자체가 없다. (계약 문서에서도 보장하지 않는다고 명시)

결정:
- **S3 구현 시점**: 기존 파일 큐를 유지한다. 큐 경로를 `app.storage.cleanup-queue-path`로 분리하고 compose 볼륨에 둔다.
- **이후 보완**: `storage_cleanup_tasks` 테이블로 옮긴다. 영수증 삭제와 **같은 트랜잭션에서** 작업 행을 넣으면(outbox) "커밋됐는데 삭제 기록이 없는" 구간이 사라진다. 스케줄러가 행을 읽어 삭제하고, 성공하면 행을 지운다. DB에 없는 S3 키를 찾아 지우는 주기 대조 작업도 추가한다.

## 4. 버킷 구성과 보안 설정

| 항목 | 설정 | 이유 |
|---|---|---|
| 버킷 | `itemsplit-receipts-prod-<무작위 접미사>`, 리전 `ap-northeast-2` (서울) | 환경별로 분리해 개발 중 실수가 운영 데이터에 닿지 않게 |
| 개발 버킷 | 필요할 때만 `...-dev-...` | 평소 로컬 개발은 `local` 저장소 사용 |
| 퍼블릭 액세스 차단 | 4개 항목 모두 ON | 원본은 presigned URL로만 접근 |
| 객체 소유권 | Bucket owner enforced (ACL 끔) | ACL로 실수로 공개되는 것 방지 |
| 암호화 | SSE-S3 (기본값) | 추가 비용 없음 |
| 버전 관리 | **끔** | 켜면 삭제해도 이전 버전이 남아, 지운 영수증 사진이 실제로는 남음 |
| 수명 주기 | 완료되지 않은 멀티파트 업로드 1일 후 정리. 객체 만료 규칙은 쓰지 않음 | 업로드 중단 찌꺼기 비용 방지. 객체를 S3가 직접 지우면 DB의 `receipt_images`와 어긋남 |
| 객체 키 | `receipts/{roomId}/{UUID}.{ext}` — 확장자는 원본 파일명이 아니라 **검사로 확인한 형식**에서 | 원본 파일명이 키로 새지 않게, 방 단위 정리가 쉽게 |
| PutObject 메타데이터 | `Content-Type` = 확인한 형식, `Content-Disposition: inline` | presigned URL로 열 때 브라우저가 이미지로 표시 |

## 5. 접근 권한 (IAM)

앱이 쓰는 권한만 준다. 버킷 삭제·정책 변경 권한은 주지 않는다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::itemsplit-receipts-prod-xxxx/receipts/*"
    }
  ]
}
```

- 주기 대조 작업을 추가할 때 `s3:ListBucket`(조건 `s3:prefix = receipts/`)을 추가한다.
- presigned URL은 서명한 주체(앱 역할)의 권한을 따르므로 앱 역할에 `GetObject`가 필요하다.
- 자격 증명: **EC2 인스턴스 프로파일(IAM 역할)** [가정: 운영 배포는 EC2]. 액세스 키를 `.env`·코드에 두지 않고, SDK 기본 자격 증명 체인이 찾게 한다.
- 개발용 액세스 키가 꼭 필요하면 위 정책만 붙인 별도 IAM 사용자를 쓰고, 키는 각자 PC의 `~/.aws/credentials`에만 둔다.

## 6. 애플리케이션 설정

```yaml
app:
  storage:
    type: ${STORAGE_TYPE:local}          # local | s3
    read-url-ttl: 5m
    cleanup-queue-path: ${STORAGE_CLEANUP_QUEUE:./storage/.cleanup-pending}
    local:
      root-path: ${LOCAL_STORAGE_ROOT:./storage}
      url-signing-secret: ${LOCAL_STORAGE_URL_SECRET:}
    s3:
      bucket: ${S3_BUCKET:}
      region: ${AWS_REGION:ap-northeast-2}
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 11MB
```

- `LocalStorageService`는 `@ConditionalOnProperty(app.storage.type=local, matchIfMissing=true)`, `S3StorageService`는 `type=s3`일 때만 빈으로 등록한다.
- `type=s3`인데 `bucket`이 비어 있으면 앱 시작을 막는다.
- 라이브러리: AWS SDK for Java v2 (`software.amazon.awssdk:s3`, BOM으로 버전 고정). Spring Cloud AWS는 필요한 기능에 비해 의존성이 커서 쓰지 않는다.

### 업로드 크기 통일 (10MB)

| 위치 | 현재 | 변경 |
|---|---|---|
| nginx `client_max_body_size` | 기본 1MB | `12m` (multipart 오버헤드 여유) |
| Spring multipart | 기본 1MB / 10MB | 10MB / 11MB |
| 예외 처리 | `MaxUploadSizeExceededException` 핸들러 없음 | `GlobalExceptionHandler`에 추가, 413 |
| 화면 | 검사 없음 | 올리기 전에 JPEG 변환·해상도 조절 후 크기 검사 |

### 허용 형식 검사

JPEG(`FF D8 FF`), PNG(`89 50 4E 47`)만 파일 앞부분 바이트로 확인해 받는다. WEBP·HEIC 등은 CLOVA OCR이 지원하지 않으므로 화면에서 JPEG로 변환해 올리고, 서버로 들어오면 거부한다.

화면 변환 시 참고:
- canvas에 그린 뒤 JPEG로 내보내면 위치(GPS) 등 EXIF 정보가 함께 제거된다.
- 세로로 찍은 사진이 회전되지 않는지 확인한다. 최신 브라우저는 EXIF 회전 정보를 반영해 그린다.

## 7. 테스트

| 대상 | 방법 |
|---|---|
| `S3StorageService` | Testcontainers LocalStack (`org.testcontainers:localstack`). `CommonContractMigrationTest`처럼 `disabledWithoutDocker = true` → AWS 계정 없이 테스트 가능 |
| 서명 URL (로컬) | 만료·서명 위조·다른 키 접근 거부 단위 테스트 |
| 조회 권한 | 방 멤버 200 / 비멤버 403 / 공유 토큰 거부 / 이미지 없는 영수증 빈 배열 |
| 크기·형식 | 초과 시 413, WEBP 등 허용하지 않는 형식 거부, 확장자만 바꾼 파일 거부 |
| 장수 | 4번째 업로드 400, 같은 영수증에 동시 업로드해도 3장 초과 저장 안 됨, 개별 삭제 후 다시 추가 가능 |

## 8. 운영 환경

| 항목 | 내용 |
|---|---|
| AWS 계정 | 개발·테스트는 LocalStack으로 하고, 실제 계정은 배포 전에 준비한다. 신규 계정 프리 티어 조건은 가입 전 AWS 공식 안내를 확인한다 |
| 비용 | S3 Standard 서울 리전 저장 GB당 월 약 $0.025. 사진 3MB × 1,000장 ≈ 3GB → 월 $0.1 수준. 영수증당 3장이어도 비용 부담은 작음. 비용은 주로 서버·DB 상시 가동에서 발생 |
| 보관 | 영수증·방 삭제 시까지. 기간 삭제를 도입하면 앱이 `receipt_images` 행과 객체를 함께 지움 |
| 캐시 | presigned URL이 매번 바뀌어 브라우저 캐시가 맞지 않음. 수정 화면 용도라 문제없음. 필요하면 이후 CloudFront 검토 |

## 9. 정해야 할 것

| 항목 | 현재 | 영향 |
|---|---|---|
| OCR 호출 위치와 이미지 전달 방식 | [가정] 서버가 OCR을 호출, 파일·URL 둘 다 지원 가능 | 업로드 경로 재검토 여부 |
| 운영 배포 위치 | [가정] EC2 | IAM 자격 증명 방식 |
| 기존 이미지 데이터 이전 | `receipts`의 이미지 컬럼을 `receipt_images`로 옮기는 방법 | DB 변경 스크립트와 기존 업로드 API 응답 |
| 나눠 찍은 사진의 경계 품목 중복 | 미정 | OCR 결과 병합 방식 |
