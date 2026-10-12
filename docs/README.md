# 개발 문서 안내

PR #16 이후의 S3·OCR 설계 보고서 5개에서 현재 채택한 내용을 정리했다. OCR 정책은 PR #19의 week4 최종안, DB 관리 절차는 PR #20의 Flyway 전환을 기준으로 한다. `report/` 원본은 수정·삭제하지 않고 당시 설계 기록으로 보존한다.

## 문서와 적용 범위

| 문서 | 역할 |
|---|---|
| [OCR 상태·DB·API 최종 설계](ocr/state-db-api-design.md) | OCR 작업·초안·반영·권한·수정 요청·품목 이동·정산 정책의 기준 |
| [OCR 추출·수정 화면 요구사항](ocr/ocr-extraction-review-requirements.md) | 최종 설계를 사용하는 화면 표시·검토·편집·오류 안내 |
| [CLOVA OCR 연동 설계](ocr/clova-ocr-integration-design.md) | 공급자 호출·응답 매핑·오류 처리·연동 검증 |
| [이미지 저장·조회·삭제 설계](storage/image-storage-design.md) | 로컬·S3 저장소, 이미지 API·권한·보관·삭제 복구·운영 설정 |
| [공통 API·DB 계약](api-db-contract.md) | 금액·멤버·저장·품목 합계 정산 계약. 아래 설계 전환 범위 확인 필요 |
| [실행·DB 마이그레이션 절차](../README.md#db-스키마-변경) | 현재 Flyway 실행·백업·새 버전 스크립트 작성 방법 |

## 구현 상태와 설계 우선순위

PR #20으로 Flyway 전환은 반영되었지만 이 문서 정리가 S3·OCR 기능 구현을 뜻하지는 않는다. 기존 동작 확인은 공통 계약과 코드를 기준으로 하고, 앞으로 구현할 S3·OCR 기능은 위 최종 설계를 따른다.

공통 계약 v1.2는 수기·OCR 영수증의 별도 총액 입력과 불일치 정산 차단을 제거하고 품목 합계를 기준으로 한다. OCR `totalAmount` 후보와 `OCR_TOTAL_MISMATCH` 경고는 유지한다. 사진별 최초 포함 최대 3회, 영수증당 하루 15회·사용자당 하루 60회와 DB 사용량 보존을 적용하며 통합 V1 이후 V2 마이그레이션으로 갱신한다. 최대 3장 × 3회로 영수증 누적 최대는 9회이므로 일일 15회는 현재 도달하지 않는 추가 안전장치다.

업로더·수정 요청 방식의 전체 권한 전환, 품목 이동, 서비스 전체 일일 상한과 전용 상태 조회 제한 등은 별도 설계 범위다. 이번 총액·일일 한도 변경으로 최종 설계 전체가 구현되었다고 해석하지 않는다.

금액 상한, `RoomMember.id`, 명시적 정산 제외, 원자적 저장과 중복 반영 방지는 유지한다. 정산 차단 여부는 `ready`·`issues`로, 대기 수정 요청 안내는 계산·공유를 차단하지 않는 `holds`로 구분한다. 공통 계약 갱신 범위는 [최종 설계 §11](ocr/state-db-api-design.md#11-공통-계약에-반영할-항목)을 따른다.

이후 정책 변경은 최종 설계와 해당 상세 문서를 함께 갱신한다. 미결정 사항은 [최종 설계 §12](ocr/state-db-api-design.md#12-정해야-할-것)에 남기며 임의로 확정하지 않는다.

## 보고서에서 정리한 내용

| 원본 | 현재 문서·처리 |
|---|---|
| [S3 요구사항](../report/backend-s3-image-design/week2-s3-requirements.md) | 이미지 저장 설계로 통합. 형식·크기·장수·보관·API 유지 |
| [S3 구조 설계](../report/backend-s3-image-design/week3-s3-design.md) | 이미지 저장 설계로 통합. OCR의 서버 파일 전달·업로더 권한·이미지 잠금과 Flyway 반영. 과거 상태·비용 추정·이미 정해진 미결정 항목 제외 |
| [OCR 화면 요구사항](../report/backend-ocr-design/ocr-extraction-review-requirements.md) | 화면 세부사항 보존. 총액·재인식·버전 검사·수정 권한을 최종 정책에 맞춤 |
| [CLOVA 연동 설계](../report/backend-ocr-design/clova-ocr-integration-design.md) | 공급자 매핑·연동 검증 보존. 작업·반영 정책은 최종 설계를 참조 |
| [week4 설계](../report/backend-ocr-draft-design/week4-ocr-state-db-api-design.md) | OCR 상태·DB·API 최종 설계로 복사·정리. 문서 연결과 Flyway 절차 갱신 |

PR #16 이전의 CRUD·인증·프론트엔드 작업 보고서는 이번 정리 대상에 포함하지 않는다.
