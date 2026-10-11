# S3 저장 구현체와 이미지 업로드 구현 기록

[이미지 저장 설계](../../docs/storage/image-storage-design.md)와 [OCR 상태·DB·API 최종 설계](../../docs/ocr/state-db-api-design.md) 가운데 이미지 업로드와 저장소 부분을 구현한 기록이다. 기준 코드는 `main` db4fa7b다.

## 구현한 것

| 영역 | 내용 |
|---|---|
| DB | `V3__receipt_images.sql`: `receipt_images` 테이블, `receipts.created_by_user_id`, 기존 이미지 컬럼 값을 `receipt_images`로 복사 |
| 업로드 | `POST /receipts/image`는 영수증과 첫 이미지를 만들고 올린 사람을 생성자와 기본 결제자로 기록. `POST /receipts/{receiptId}/images`는 한 장 추가 |
| 검사 | 파일 앞부분 바이트로 JPEG·PNG 확인, 한 장 10MB(초과 시 413), 영수증당 3장 |
| 권한 | 이미지 추가는 `IMAGE_UPLOAD` 영수증에서 올린 사람만. 올린 사람 기록이 없는 기존 영수증은 방 멤버 누구나 |
| 저장소 | `StorageService`에 `read` 추가, 저장 이름의 확장자와 형식을 검사 결과로 결정. `S3StorageService` 추가, `app.storage.type`으로 `local`·`s3` 선택 |
| 삭제 | 영수증·방 삭제 시 `receipt_images` 행을 지우고 모든 이미지 객체를 커밋 후 삭제 |
| 설정 | Spring multipart 10MB·11MB, nginx `client_max_body_size 12m`, `STORAGE_TYPE`·`S3_BUCKET`·`AWS_REGION`·`STORAGE_CLEANUP_QUEUE` |

## 응답 형식

`POST /api/rooms/{roomId}/receipts/image` (201)

```json
{
  "receiptId": 21, "roomId": 1, "name": "저녁 식사", "sourceType": "IMAGE_UPLOAD",
  "payerMemberId": 12, "createdByUserId": 3,
  "image": { "imageId": 41, "receiptId": 21, "order": 1, "originalFilename": "photo.jpg", "contentType": "image/jpeg", "fileSize": 204800 }
}
```

`POST /api/rooms/{roomId}/receipts/{receiptId}/images` (201)은 위의 `image` 객체와 같은 형식이다. 이전 응답의 `storedPath`는 없앴다.

| 상황 | 응답 |
|---|---|
| JPEG·PNG가 아니거나 내용이 이미지가 아님, 네 번째 이미지, 수기 영수증 | 400 `VALIDATION_ERROR` |
| 올린 사람이 아닌 멤버의 이미지 추가, 방 멤버가 아님 | 403 `FORBIDDEN` |
| 다른 방의 영수증 | 404 `NOT_FOUND` |
| 10MB 초과 | 413 `PAYLOAD_TOO_LARGE` |

## 설계와 다르게 둔 것

- `receipts`의 이미지 컬럼 네 개는 DB에 남겨 두고 코드에서만 쓰지 않는다. 삭제는 배포 확인 뒤 새 버전 스크립트로 한다.
- 업로드 중에는 방 잠금을 잡은 채 저장소에 쓴다. 장수 검사와 저장이 한 트랜잭션에 있어 동시 업로드로 3장을 넘길 수 없지만, 그동안 같은 방의 다른 저장 요청은 기다린다.
- 수기 영수증에도 생성자를 기록한다. 권한 판단에는 쓰지 않는다.

## 이번에 넣지 않은 것

- 이미지 목록 조회와 단기 조회 URL(`createReadUrl`), 이미지 개별 삭제, 영수증 응답의 `imageCount`·`createdByUserId`
- 인식 시작 후 이미지 잠금(`IMAGES_LOCKED`): 인식 작업 테이블이 생길 때 함께 넣는다
- 삭제 재시도 큐의 DB 이전과 객체 대조 작업
