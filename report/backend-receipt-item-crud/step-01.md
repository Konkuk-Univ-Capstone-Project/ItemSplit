# Step 1 Report

## 목적

origin/main을 반영하고, Receipt 엔티티를 수동 입력 방식도 수용할 수 있도록 일반화한다.

## 적용 내용

- `Receipt` 엔티티 필드 추가
  - `payer` (`@ManyToOne User`): 영수증을 실제로 결제한 사람을 기록한다. 정산 시 누가 먼저 낸 것인지 파악하기 위한 필드이다. 선택 입력이므로 nullable이다.
  - `declaredTotal` (`Integer`): 영수증에 적힌 총액을 직접 입력받는 필드이다. 품목 합산과 비교해 불일치 여부를 검토하기 위한 용도이다. 선택 입력이므로 nullable이다.

- `purchasedAt` (`LocalDate`): 사용자가 직접 입력하는 구매 날짜이다. 어제 결제한 영수증을 오늘 등록하는 경우처럼 `createdAt`(시스템 등록 시각)과 실제 구매 날짜가 달라질 수 있으므로 별도 필드로 분리했다. 선택 입력이므로 nullable이다.

- `Receipt.createManual()` 정적 팩토리 메서드 추가
  - `sourceType=MANUAL`로 고정해 OCR 방식과 수동 방식을 명확히 구분한다.
  - payer, declaredTotal은 null 허용으로 두어 상호명만으로도 생성이 가능하게 했다.

- `Receipt.update()` 메서드에 payer, declaredTotal 포함
  - 수정 시 결제자와 총액도 함께 갱신할 수 있도록 했다.

## 검증 결과

- `./gradlew build` 성공
