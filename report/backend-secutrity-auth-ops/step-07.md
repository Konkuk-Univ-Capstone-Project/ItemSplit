# Step 07 - Assignment Test Coverage

## 목표
- Sprint 3 B 역할의 완료 기준인 Assignment API 테스트를 핵심 정상/실패 시나리오까지 확장한다.
- Assignment API가 공통 응답/에러 규격을 지키는지 확인한다.
- 이후 A가 정산 엔진을 붙일 때 참여자 지정 데이터가 안정적으로 조회된다는 신뢰를 테스트로 보장한다.

## 이번 단계에서 추가한 내용

### 1. 정상 흐름 테스트 확장
- item assignees를 `[A, B]`로 설정한 뒤 `[B, C]`로 다시 교체하는 케이스를 검증했다.
- 교체 이후 조회 API가 최신 assignee 목록만 반환하는지도 함께 확인했다.
- 빈 배열 `[]`을 보내면 기존 assignees가 모두 해제되는 정책도 테스트로 고정했다.

### 2. 인증/권한 실패 테스트
- 인증 없이 조회 요청하면 `401 UNAUTHORIZED`가 반환되는지 검증했다.
- 방 멤버가 아닌 사용자가 교체 요청하면 `403 FORBIDDEN`이 반환되는지 검증했다.
- 다른 방 멤버를 assignee로 넣는 경우 `VALIDATION_ERROR`가 반환되는지도 확인했다.

### 3. 미존재/경로 불일치 테스트
- 존재하지 않는 `roomId`, `receiptId`, `itemId`, `memberId` 각각에 대해 실패 응답을 검증했다.
- `receipt`가 해당 `room`에 속하지 않는 경우, `item`이 해당 `receipt`에 속하지 않는 경우도 각각 검증했다.
- 이 테스트들로 중첩 URL 경로가 실제 도메인 소속관계와 일치해야만 성공하도록 보장했다.

### 4. 공통 응답 포맷 검증
- 성공 응답은 `success=true`와 `data` 구조를 갖는지 확인했다.
- 인증/권한/미존재/validation 실패 응답은 `success=false`와 `error.code`, `error.message` 구조를 따르는지 확인했다.
- `memberIds`가 `null`인 요청은 Bean Validation을 통해 `VALIDATION_ERROR`와 field detail이 내려가는지 검증했다.

### 5. 테스트 픽스처 정리
- Assignment 도입으로 `Assignment -> Item -> Receipt -> RoomMember -> Room -> User` 순의 의존성이 생겨, 관련 테스트들의 정리 순서를 보정했다.
- 이 보정으로 Assignment 테스트뿐 아니라 기존 Auth/Room/Receipt 테스트도 전체 스위트 기준으로 다시 안정적으로 통과하도록 만들었다.

## 정리
- Sprint 3 B 역할 기준으로 Assignment API와 핵심 통합 테스트는 완료 판단이 가능하다.
