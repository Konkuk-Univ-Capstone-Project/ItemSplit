# Step 4 Report

## 목적

영수증 단건 조회 시 선언 총액과 품목 합계가 다를 경우 경고를 내려준다.

## 적용 내용

- `warning` 필드를 `ReceiptDetailResponse`에 추가
  - 에러가 아닌 경고 수준으로 처리했다. 총액 불일치가 있어도 정상 응답(200)을 내려주고, 클라이언트가 UI에서 안내 문구를 표시할 수 있도록 했다.

- 경고 조건
  - `declaredTotal`이 null이면 비교 대상이 없으므로 경고 없음
  - `declaredTotal`이 입력되어 있고 품목 합계(`price * quantity` 총합)와 다를 경우 경고 문구를 포함한다.
  - 경고 문구 예시: `"검토 필요: 입력한 총액(10000)과 품목 합계(9000)가 다릅니다."`

- `total_amount` 컬럼 없음
  - Receipt 엔티티에 총액 컬럼을 두지 않는다. 품목의 `price * quantity` 합산이 source of truth이고, `declaredTotal`은 어디까지나 사용자가 입력한 참고값이다.

## 검증 결과

- `./gradlew build` 성공
