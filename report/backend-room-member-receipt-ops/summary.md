# 방 멤버 기반 영수증/정산 및 멤버 관리 API

## Summary

`main` 브랜치 대비 영수증 결제자, 품목 참여자, 정산 계산 기준을 `User` 중심에서 `RoomMember` 중심으로 전환했습니다. 이를 통해 계정이 아직 연결되지 않은 수동 멤버도 결제자와 참여자로 지정할 수 있고, 초대 토큰으로 들어온 사용자가 기존 수동 멤버와 매칭해 방에 참여할 수 있습니다.

또한 방/멤버 관리 API를 확장해 수동 멤버 추가/수정/삭제, 방 삭제, 초대 참여 옵션 조회를 지원합니다.

## 주요 변경

- `RoomMember`에 `displayName`과 수동 멤버 개념을 추가했습니다.
- 방 멤버 응답에 `memberId`, `linked` 정보를 추가했습니다.
- 영수증 결제자를 `payer_id(User)` 기준에서 `payer_member_id(RoomMember)` 기준으로 변경했습니다.
- 품목 참여자 배정을 `user_id(User)` 기준에서 `room_member_id(RoomMember)` 기준으로 변경했습니다.
- 정산 계산과 공유 정산 응답도 `RoomMember` 기준으로 계산/반환하도록 변경했습니다.
- 기존 사용자 ID 기반 참여자 요청과의 호환을 위해 참여자 저장 시 `RoomMember ID` 우선, 필요 시 `User ID`도 해석하도록 처리했습니다.

## API 변경

- `POST /api/rooms/{roomId}/members/manual`
  - 수동 멤버를 추가합니다.
- `PUT /api/rooms/{roomId}/members/{memberId}`
  - 멤버 표시 이름을 수정합니다.
- `DELETE /api/rooms/{roomId}/members/{memberId}`
  - owner를 제외한 멤버를 방에서 제외합니다.
  - 삭제 대상이 영수증 결제자인 경우, 결제자로 지정된 영수증 이름을 모두 포함한 검증 메시지를 반환합니다.
- `DELETE /api/rooms/{roomId}`
  - owner만 방과 관련 데이터를 삭제할 수 있습니다.
- `GET /api/rooms/join-options?token=...`
  - 초대 토큰으로 참여 가능한 방 정보와 수동 멤버 매칭 후보를 조회합니다.
- `POST /api/rooms/join`
  - `memberId`, `nickname` 파라미터를 통해 수동 멤버 매칭 또는 새 멤버 참여를 지원합니다.

## 데이터/호환성

- `local` 프로필에서 기존 로컬 스키마와의 호환을 위한 `LocalSchemaCompatibilityMigration`을 추가했습니다.
- 기존 `receipts.payer_id` 값은 동일 방의 `room_members.user_id`와 매칭해 `payer_member_id`로 보정합니다.
- `room_members.user_id`, `assignments.user_id`, `assignments.room_member_id`의 로컬 NOT NULL 제약을 완화합니다.

## 테스트

- `RoomControllerTest`
  - 방 삭제
  - 수동 멤버 추가/수정/삭제
  - 초대 토큰 참여 옵션 조회 및 수동 멤버 매칭
  - owner 삭제 차단
  - 일반 멤버 자기 자신/다른 일반 멤버 삭제
  - 결제자로 지정된 멤버 삭제 차단 메시지
- `ReceiptCrudControllerTest`
  - `RoomMember ID` 기반 결제자 수정/생성
  - 수동 멤버 결제자 지정
- `AssignmentControllerTest`
  - 수동 멤버 참여자 지정
- `SettlementControllerTest`, `RoomShareControllerTest`
  - `RoomMember` 기준 정산 계산 및 공유 응답 검증


