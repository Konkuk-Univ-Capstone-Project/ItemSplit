# 영수증 이미지 저장·조회·삭제 설계

PR #17의 S3 요구사항·구조 설계를 통합하고, PR #19의 OCR 최종 정책과 PR #20의 Flyway 전환에 맞춘 현재 설계다. 이 문서는 구현할 동작을 정의하며 S3·다중 이미지 API의 구현 완료를 의미하지 않는다. OCR 권한·이미지 잠금·데이터 모델은 [OCR 상태·DB·API 최종 설계](../ocr/state-db-api-design.md)를 따른다.

## 1. 입력과 보관

| 항목 | 기준 |
|---|---|
| 형식 | JPEG·PNG. 서버가 헤더뿐 아니라 파일 앞부분 바이트로 형식을 검사 |
| 다른 형식 | HEIC·HEIF·WEBP는 화면에서 JPEG로 변환. 변환 실패 시 설명과 재선택 제공 |
| 크기·장수 | 장당 최대 10MB, 영수증당 최대 3장. 한 요청에 한 장씩 업로드 |
| 대상 | `IMAGE_UPLOAD` 영수증만 이미지 추가·OCR 가능. `MANUAL`은 수기 입력만 허용 |
| 업로더 | 영수증 생성 사용자(`created_by_user_id`). 한 영수증의 사진은 한 사람이 업로드 |
| 객체 키 | 서버가 `receipts/{roomId}/{UUID}.{ext}` 생성. 확장자는 검사한 형식 사용 |
| 메타데이터 | `receipt_images`에 객체 키·원본 파일명·형식·크기·순서 저장. 조회 URL은 저장하지 않음 |
| 보관 | 영수증·방 삭제 시까지. 이미지 개별 삭제는 OCR 시작 전만 허용. OCR 반영 완료로 삭제하지 않음 |

변환 해상도·JPEG 품질은 아직 미정이다. 변환 후 미리보기·회전 방향·크기를 확인하며, 별도 OCR 정확도 벤치마크는 수행 범위에 넣지 않는다. 원본 미리보기는 업로드한 이미지를 뜻하고 변환 전 HEIC 파일까지 보존한다는 뜻은 아니다.

## 2. 권한과 이미지 변경

- JWT와 방 멤버 권한을 확인한다. 이미지 조회는 방 멤버에게 허용하고 공유 토큰으로는 허용하지 않는다.
- 업로더 기록이 있는 이미지 영수증의 사진 추가·삭제는 업로더만 수행한다. 다른 멤버는 403이다. 업로더 기록이 없는 기존 영수증의 예외는 최종 설계 §6.1을 따른다.
- OCR 작업 생성 후에는 완료·실패 여부와 무관하게 이미지 추가·삭제를 409 `IMAGES_LOCKED`로 거부한다.
- 사진 교체나 성공 결과 재인식은 새 영수증 생성으로 처리한다. 재시도 가능한 오류의 실패 사진만 기존 작업에서 재시도한다.
- 장수 검사와 이미지 변경은 OCR 시작·영수증 삭제와 충돌하지 않게 직렬화한다. 최종 설계의 방 잠금 규칙을 따르고, 영수증 행 잠금도 사용할 경우 방 → 영수증 순서를 지킨다. 동시 업로드로 3장 제한을 우회하지 못하게 한다.

## 3. 업로드와 API

브라우저가 multipart로 백엔드에 전송하고, 백엔드가 권한·형식·크기·장수를 검사한 뒤 저장소에 저장한다. 브라우저에 presigned PUT URL을 발급하는 방식은 채택하지 않는다. 객체 저장 후 DB 저장이 롤백되면 `deleteAfterRollback`으로 객체를 정리한다.

| API | 동작 |
|---|---|
| `POST /api/rooms/{roomId}/receipts/image` | 첫 이미지와 영수증 생성. 생성자 기록, 결제자 기본값은 생성자의 방 멤버. 응답에서 `storedPath` 제거 |
| `POST /api/rooms/{roomId}/receipts/{receiptId}/images` | 기존 이미지 영수증에 한 장 추가. 수기 영수증·3장 초과는 400, 권한·잠금은 §2 적용 |
| `GET /api/rooms/{roomId}/receipts/{receiptId}/images` | 순서대로 이미지 정보와 단기 조회 URL 반환. 없으면 빈 배열 |
| `DELETE /api/rooms/{roomId}/receipts/{receiptId}/images/{imageId}` | 인식 시작 전 이미지 한 장 삭제. DB 커밋 후 객체 정리 |
| 영수증 목록·상세 조회 | `imageCount`와 최종 설계의 생성자·OCR 요약 필드 제공 |
| 영수증·방 삭제 | 관련 이미지·OCR 데이터 삭제 후 커밋 시 객체 정리. 이미지 잠금은 영수증·방 삭제 금지를 뜻하지 않음 |

모든 API는 기존 `success`·`data`·`error` 응답 형식을 사용한다. 아래는 이미지 목록 응답의 `data` 예시다.

```json
[
  { "imageId": 41, "order": 1, "url": "https://...", "expiresAt": "2026-10-11T12:05:00Z", "contentType": "image/jpeg" },
  { "imageId": 42, "order": 2, "url": "https://...", "expiresAt": "2026-10-11T12:05:00Z", "contentType": "image/png" }
]
```

DB의 `sort_order`는 최댓값 + 1로 부여하고 삭제 후 다시 매기지 않는다. 응답의 `order`는 저장된 순서에 따라 1부터 센 표시용 값이다. 이미지·영수증·방 사이의 소속 관계도 검사한다.

## 4. 조회와 OCR의 저장소 사용

| 용도 | 방식 |
|---|---|
| S3 미리보기 | 권한 확인 후 `S3Presigner`로 5분 유효 presigned GET URL 생성 |
| 로컬 미리보기 | `/api/storage/local/{key}?expires=...&sig=...`. 키·만료 시각을 HMAC-SHA256으로 서명하고 파일 요청에서 검사 |
| OCR 입력 | 서버가 객체 키로 바이트를 읽고 CLOVA에 파일로 전달. 미리보기 URL을 OCR 입력으로 사용하지 않음 |

이미지 목록 API에서 JWT 권한을 확인하며, `<img src>`의 파일 요청은 단기 서명으로 보호한다. 로컬 URL 서명 키는 JWT 키와 분리한다. URL이 만료되면 화면은 이미지 목록을 다시 조회한다. 서명 URL·이미지 바이트는 일반 로그에 남기지 않는다.

저장소 인터페이스는 기존 저장·삭제에 조회 URL 생성과 OCR용 읽기를 추가한다.

```java
public interface StorageService {
    StoredFile store(String directory, MultipartFile file) throws IOException;
    void delete(String storedPath) throws IOException;
    SignedUrl createReadUrl(String storedPath, Duration ttl);
    byte[] read(String storedPath) throws IOException;
    record SignedUrl(String url, Instant expiresAt) { }
}
```

클라이언트가 외부 URL이나 객체 키를 지정해 OCR을 호출하게 하지 않는다. 이미지는 `imageId`로 식별하고 서버가 저장된 키를 조회한다. 한 장 최대 10MB이므로 OCR은 바이트 배열로 읽는다.

## 5. 삭제와 실패 복구

- 영수증·방·허용된 개별 이미지 삭제는 DB 커밋 후 객체를 지운다. DB 트랜잭션이 저장소 작업까지 되돌린다고 가정하지 않는다.
- S3 도입 시 기존 파일 기반 삭제 재시도 큐를 유지하되 `app.storage.cleanup-queue-path`로 경로를 분리하고 영속 볼륨에 둔다. 실패 작업은 시작 시 및 60초 간격으로 재시도한다.
- 커밋 직후 프로세스 종료로 삭제 기록 자체가 남지 않는 구간은 파일 큐만으로 보장하지 못한다.
- 후속 보완은 `storage_cleanup_tasks` outbox와 DB에 없는 객체를 찾는 주기적 대조다. outbox는 영수증 삭제와 같은 트랜잭션으로 작업을 기록한다. 아직 초기 S3 구현의 보장으로 표현하지 않는다.
- 객체 수명 주기 만료로 영수증 이미지만 자동 삭제하지 않는다. 기간 보관 정책을 추가할 때는 DB 이미지 행과 객체를 함께 정리하도록 설계를 변경한다.

## 6. DB 변경과 기존 이미지 이전

`receipt_images`의 컬럼·제약과 기존 이미지 복사 SQL은 [최종 설계 §3](../ocr/state-db-api-design.md#3-db-변경안)을 따른다. 기존 `receipts`의 이미지 컬럼 네 개를 새 테이블로 옮기고, 코드 전환 후 구 컬럼은 사용하지 않는다. 기존 이미지의 형식은 OCR 시작 시 다시 검사한다.

DB 변경은 다음 번호의 `src/main/resources/db/migration/V{번호}__{설명}.sql`에 작성한다. 이미 main에 반영된 마이그레이션을 수정하지 않는다. 앱 시작 시 Flyway가 적용하고 Hibernate는 `validate`만 수행한다. 구 컬럼 삭제도 별도 새 버전으로 진행한다. 백업·실행 절차는 [README](../../README.md#db-스키마-변경)를 따른다.

## 7. 설정과 운영

아래는 구현 시 추가할 설정이다.

```yaml
app:
  storage:
    type: ${STORAGE_TYPE:local}
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

- 저장소는 `local|s3` 설정에 따라 선택하고 기본은 `local`이다. S3 선택 시 버킷 설정이 없으면 시작을 막는다.
- nginx의 `client_max_body_size`는 multipart 여유를 포함한 `12m`, Spring은 파일 10MB·요청 11MB로 설정한다. 화면의 파일 제한도 10MB이며 크기 초과는 413으로 처리한다.
- AWS SDK for Java v2의 S3 모듈과 BOM을 사용한다. 로컬 개발·자동 테스트는 실제 AWS 계정 없이 수행한다.
- 비공개 환경별 버킷, 서울 리전, 퍼블릭 액세스 차단, Bucket owner enforced, SSE-S3를 사용한다. 버전 관리는 끄고, 객체 만료 규칙 대신 미완료 멀티파트 업로드를 1일 후 정리한다.
- 저장 시 `Content-Type`은 검사한 형식, `Content-Disposition`은 `inline`으로 설정한다.
- 앱 IAM에는 해당 버킷의 `receipts/*`에 대한 `s3:PutObject`·`s3:GetObject`·`s3:DeleteObject`만 부여한다. 주기 대조를 도입할 때 필요한 범위의 `s3:ListBucket`을 추가한다.
- 운영 배포가 EC2라는 전제는 아직 가정이다. EC2 사용 시 인스턴스 역할과 SDK 기본 자격 증명 체인을 사용한다. 개발용 키가 필요하면 제한된 권한으로 로컬 자격 증명 파일에만 두고 저장소에 기록하지 않는다.
- 성능 목표는 10MB 업로드의 서버 처리 3초 이내이며 검증된 성능 수치가 아니다. URL 발급은 이미지 메타데이터 조회와 서명 계산으로 처리한다.

확정이 필요한 항목은 변환 품질·해상도와 실제 운영 배포 환경이다. OCR 호출 위치·파일 전달 방식, 사진 경계 중복 표시, 기존 이미지 이전 절차는 최종 설계에서 정했으므로 미정으로 남기지 않는다. 과거 보고서의 비용 추정치를 현재 요금으로 사용하지 않는다.

## 8. 검증 기준

| 상황 | 확인 내용 |
|---|---|
| S3 저장·조회·삭제 | LocalStack/Testcontainers에서 검증. Docker 실행 필요. Docker가 없으면 건너뛸 수 있으므로 실제 실행 여부 확인 |
| 로컬 서명 URL | 만료·서명 변조·다른 키 접근 거부 |
| 권한 | 방 멤버 조회 허용, 비멤버·공유 토큰 거부, 업로더 아닌 멤버의 이미지 변경 거부 |
| 입력 | 실제 형식 검사, 크기 초과 413, 확장자만 바꾼 파일 거부, 수기 영수증 이미지 추가 거부 |
| 장수·동시성 | 네 번째 이미지 400. 동시 추가 시 3장 상한 유지, 인식 시작과 이미지 변경 직렬화 |
| 이미지 잠금 | 작업 생성 이후 개별 추가·삭제 409. OCR 실패·반영 완료 후에도 잠금 유지 |
| 삭제 | 인식 시작 전 개별 삭제·재추가 가능, 영수증·방 삭제 시 이미지·OCR 행과 객체 정리 |
| 실패 복구 | DB 롤백 시 새 객체 정리, 삭제 실패 재시도, 컨테이너 재생성 후 큐 보존 |
| 빈 DB·기존 DB | Flyway 변경 적용과 Hibernate 검증 통과, 기존 이미지 메타데이터 보존 |

## 원본 보고서

- [S3 저장·조회·삭제 요구사항](../../report/backend-s3-image-design/week2-s3-requirements.md)
- [S3 저장 구조·접근 권한·운영 환경](../../report/backend-s3-image-design/week3-s3-design.md)
- [week4 OCR 설계](../../report/backend-ocr-draft-design/week4-ocr-state-db-api-design.md)

원본은 당시 논의와 결정의 기록으로 보존한다. 이후 구현 기준은 이 문서와 OCR 최종 설계다.
