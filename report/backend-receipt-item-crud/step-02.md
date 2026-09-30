# Step 2 Report

## 목적

영수증 수동 생성, 조회, 수정, 삭제 API를 구현한다.

## 적용 내용

- `RoomAuthorizationService` 추가
  - 방 멤버 여부를 확인하는 로직을 별도 서비스로 분리했다. Receipt, Item 등 방 안에서 동작하는 모든 API가 공통으로 재사용할 수 있도록 했다.

- `POST /api/rooms/{roomId}/receipts/manual`
  - 상호명, 결제자(payerId), 총액(declaredTotal), 구매 날짜(purchasedAt)를 입력받아 수동 영수증을 생성한다.
  - payerId, declaredTotal, purchasedAt은 선택 입력으로, 상호명만으로도 생성이 가능하다.

- `GET /api/rooms/{roomId}/receipts`
  - 방에 속한 영수증 목록을 반환한다. 방 멤버만 조회할 수 있다.

- `GET /api/rooms/{roomId}/receipts/{receiptId}`
  - 영수증 단건 상세 조회이다. 품목 목록과 함께 반환한다.

- `PUT /api/rooms/{roomId}/receipts/{receiptId}`
  - 상호명, 결제자, 총액, 구매 날짜를 수정한다. 방 멤버만 수정할 수 있다.

- `DELETE /api/rooms/{roomId}/receipts/{receiptId}`
  - 영수증을 삭제한다. 연관된 품목과 배정 데이터도 함께 삭제된다.

- 응답 DTO 추가
  - `ReceiptListResponse`: 목록 조회용으로 id, 상호명, sourceType, 생성일을 포함한다.
  - `ReceiptDetailResponse`: 단건 조회용으로 품목 목록, payerId, declaredTotal, warning까지 포함한다.

## 검증 결과

- `./gradlew build` 성공
