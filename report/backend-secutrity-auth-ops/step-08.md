# Step 08 - Assignment API Handoff Docs

## 목표
- A가 정산 엔진에서 Assignment 데이터를 바로 쓸 수 있도록 API 계약을 문서로 남긴다.
- 구현 범위는 넓히지 않고, 이미 만든 Assignment API의 사용 방법과 보장 조건을 명확히 정리한다.

## 이번 단계에서 문서화한 내용

### 1. Assignment API 계약
- README에 assignees 조회 API와 전체 교체 API를 추가로 정리했다.
- 요청 URL, 인증 방식, 요청 본문 형식을 함께 적어서 바로 호출해 볼 수 있게 했다.

### 2. 빈 배열 정책
- `memberIds: []`는 assignee 전체 해제 정책임을 README에 명시했다.
- 이 정책을 문서로 고정해서 프론트엔드나 정산 엔진이 별도 예외 규칙 없이 같은 의미로 해석할 수 있게 했다.

### 3. 샘플 응답
- 성공 응답 예시를 README에 추가했다.
- `roomId`, `receiptId`, `itemId`, `itemName`, `assignees` 구조를 확인할 수 있어서 A가 필요한 필드를 빠르게 파악할 수 있다.

### 4. A를 위한 연동 메모
- assignee 조회는 어디서 하는지, 어떤 경로로 item별 참여자를 읽는지 README에 정리했다.
- 서버가 `receipt -> room`, `item -> receipt`, `assignee -> room member` 관계를 모두 검증하므로, 성공 응답의 assignee는 해당 room 멤버라는 보장이 있다는 점도 명시했다.
- 현재 `memberIds`는 `RoomMember.id`가 아니라 `User.id` 기준이라는 점도 함께 남겼다.

## 정리
- Sprint 3 B 관점에서 Assignment API 자체뿐 아니라, A가 바로 연동할 수 있는 계약 문서까지 준비된 상태다.
- 이후 정산 엔진은 assignee 목록을 신뢰 가능한 입력으로 받아 계산 로직에만 집중하면 된다.
