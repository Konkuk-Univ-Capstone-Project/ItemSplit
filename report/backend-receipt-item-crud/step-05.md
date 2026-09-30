# Step 5 Report

## 목적

Receipt/Item CRUD 전체를 통합 테스트로 검증하고, bulk DELETE 시 발생하는 Hibernate L1 캐시 버그를 수정한다.

## 적용 내용

- `ReceiptCrudControllerTest` 추가 (7개)
  - GET 목록: 방 멤버가 영수증 목록을 조회할 수 있다.
  - GET 단건: 품목 목록이 함께 반환된다.
  - GET 단건 (경고): declaredTotal과 품목 합계가 다를 때 warning 필드가 포함된다.
  - PUT: 상호명, 결제자, 총액이 정상적으로 수정된다.
  - DELETE (cascade): 영수증 삭제 시 품목과 배정 데이터도 함께 삭제된다.
  - DELETE (비멤버): 방 멤버가 아닌 사용자는 403을 받는다.
  - POST /manual: payerId, declaredTotal이 정상 저장된다.

- `ItemControllerTest` 추가 (3개)
  - POST: 품목이 정상 생성된다.
  - PUT: 품목 이름, 단가, 수량이 수정된다.
  - DELETE (cascade): 품목 삭제 시 배정 데이터도 함께 삭제된다.

- `@Modifying` bulk DELETE L1 캐시 버그 수정
  - JPQL bulk DELETE(`@Modifying @Query`)는 DB에만 반영되고 Hibernate 1차 캐시는 비우지 않는다. 이 상태에서 flush가 발생하면 캐시에 남은 엔티티가 삭제된 부모를 참조해 `TransientObjectException`이 발생했다.
  - `@Modifying(clearAutomatically = true)`를 적용해 bulk DELETE 직후 1차 캐시를 자동으로 비우도록 수정했다.
  - 영향 범위: `AssignmentRepository.deleteAllByItemIdIn`, `ItemRepository.deleteAllByReceiptId`

## 검증 결과

- `./gradlew test` 성공
  - 48개 테스트 전부 통과
- 확인된 주요 시나리오
  - 방 멤버가 영수증 목록을 정상 조회
  - 영수증 단건 조회 시 품목 목록 포함
  - declaredTotal과 품목 합계 불일치 시 warning 필드 포함
  - 영수증 수정 시 상호명, 결제자, 총액 반영
  - 영수증 삭제 시 품목, 배정 데이터 cascade 삭제
  - 비멤버의 영수증 삭제 요청 403 차단
  - 수동 영수증 생성 시 payerId, declaredTotal 정상 저장
  - 품목 생성, 수정, 삭제 및 cascade 삭제 정상 동작

## 테스트 실행 방법

```bash
# 전체 테스트
./gradlew test

# 특정 클래스만
./gradlew test --tests "com.capstone.itemsplit.receipt.ReceiptCrudControllerTest"
./gradlew test --tests "com.capstone.itemsplit.item.ItemControllerTest"
```

- Docker 불필요 — Testcontainers가 PostgreSQL 컨테이너를 자동으로 띄우고 종료함
- 결과 리포트: `build/reports/tests/test/index.html`
