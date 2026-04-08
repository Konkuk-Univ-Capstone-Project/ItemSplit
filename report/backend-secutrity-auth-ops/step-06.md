# Step 06 - Assignment API

## 목표
- 특정 Item의 참여자를 조회하고 전체 교체할 수 있는 Assignment API를 추가한다.
- 요청자는 반드시 해당 방의 멤버여야 하며, 지정 대상도 모두 같은 방 멤버인지 검증한다.
- 정산 계산 로직은 건드리지 않고, 참여자 지정 데이터와 검증 규칙만 안정적으로 제공한다.

## 이번 단계에서 추가한 내용

### 1. Assignment API 엔드포인트
- `GET /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees`
  - 현재 item에 지정된 참여자 목록을 조회한다.
- `PUT /api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees`
  - 요청 본문의 `memberIds` 목록으로 참여자를 전체 교체한다.
  - 빈 배열은 전체 해제로 처리한다.

### 2. Assignment 서비스 계층
- `AssignmentService`를 추가해 조회와 전체 교체 로직을 모았다.
- 먼저 `RoomAuthorizationService.checkMember(roomId, requesterId)`로 요청자의 방 멤버 여부를 확인한다.
- 그다음 `receiptId`가 해당 `roomId`에 속하는지, `itemId`가 해당 `receiptId`에 속하는지 순서대로 검증한다.
- 이 검증으로 경로 불일치 요청을 일관되게 `NOT_FOUND`로 처리할 수 있다.

### 3. 멤버 검증 규칙
- 요청 본문의 `memberIds`는 현재 모델 기준 `RoomMember.id`가 아니라 `User.id`를 사용한다.
- 이유는 기존 `Assignment` 엔티티가 `User`를 직접 참조하고, 방 멤버 조회 응답도 `userId` 중심이기 때문이다.
- 존재하지 않는 사용자 ID가 섞여 있으면 `NOT_FOUND`로 처리한다.
- 존재는 하지만 같은 방 멤버가 아닌 사용자 ID가 섞여 있으면 `VALIDATION_ERROR`로 처리한다.
- 중복 ID는 서버에서 제거한 뒤 저장해서 유니크 제약과 충돌하지 않게 했다.

### 4. 저장소와 도메인 보강
- `AssignmentRepository`를 추가해서 item 기준 assignee 목록을 조회할 수 있게 했다.
- `ItemRepository`를 추가해서 item 조회를 서비스 계층에서 직접 처리할 수 있게 했다.
- `RoomMemberRepository`에는 특정 방의 특정 사용자 집합을 한 번에 조회하는 메서드를 추가했다.
- `Item`과 `Assignment` 엔티티에는 테스트와 서비스 구현에 필요한 정적 생성 메서드를 추가했다.

### 5. Assignment API 기본 검증
- `AssignmentControllerTest`를 추가해서 최소 동작 확인을 넣었다.
- room member가 assignees를 설정하고 다시 조회할 수 있는지 확인한다.
- 다른 방 사용자 ID를 assignee로 넣으면 `VALIDATION_ERROR`가 나는지도 확인한다.
