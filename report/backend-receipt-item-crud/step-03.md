# Step 3 Report

## 목적

품목 추가, 수정, 삭제 API를 구현한다.

## 적용 내용

- `POST /api/rooms/{roomId}/receipts/{receiptId}/items`
  - 영수증에 품목을 추가한다. 이름, 단가, 수량을 입력받는다.

- `PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}`
  - 품목의 이름, 단가, 수량을 수정한다.

- `DELETE /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}`
  - 품목을 삭제한다. 해당 품목에 연결된 배정 데이터도 함께 삭제된다.

- `ItemService` 추가
  - 품목 생성/수정/삭제 로직을 담당한다.
  - 삭제 시 Assignment를 먼저 제거한 뒤 Item을 삭제해 FK 제약을 지킨다.

- `ItemController` 추가
  - 모든 요청에서 JWT 인증을 통해 요청자를 식별하고, 방 멤버 여부를 검증한다.

## 검증 결과

- `./gradlew build` 성공
