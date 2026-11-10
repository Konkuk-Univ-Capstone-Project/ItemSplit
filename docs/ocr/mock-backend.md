# 실제 저장소·가짜 CLOVA를 사용하는 OCR 백엔드

이 문서는 합성 응답으로 검증하는 `ocr-demo` 실행 안내다. 실제 공급자 연동은 [CLOVA 백엔드 실행 안내](clova-backend.md)를 따른다.

## 이번 구현 범위

`이미지 업로드 → StorageService(local/S3) 저장·읽기 → 가짜 CLOVA JSON → 실제 파싱 → DB 초안 저장·조회`를 실행한다. PR 23의 저장소 구현체를 사용하며 CLOVA에는 접속하지 않는다. `STORAGE_TYPE=local`이면 AWS 계정 없이 실행할 수 있다. `s3`이면 실제 S3에 이미지 객체를 저장하고 읽는다. 사용자 확인 전 정상 `items`에는 저장하지 않는다.

기존 최종 설계 중 **인식 시작·조회·실패 재시도와 초안 생성**을 구현한 단계다. 실제 CLOVA HTTP 호출은 별도 `ocr` 프로필로 지원한다. 웹에서는 사진 업로드·인식 시작·실패 사진 재시도·계산 단가 후보 채택·초안 검토·수정·정산 반영까지 지원한다. 상태 조회는 2초→5초 간격과 429·일시 오류 재조회 정책을 적용한다. [저장·반영 API 계약](clova-backend.md#초안-저장반영-계약)을 참고한다. 수기·OCR 영수증 모두 품목 합계로 정산하며 별도 총액 입력은 제공하지 않는다. OCR 인식 총액 후보는 경고에만 사용한다. 수정 요청·품목 이동·서비스 전체 일일 상한·전용 polling 제한·사진 경계 중복 판정은 별도 구현 대상이다. 이미지별 최초 포함 최대 3회와 영수증·사용자 일일 한도 및 기존 API 요청 제한을 적용한다.

## 실행

PostgreSQL만 준비한 뒤 실행한다. Flyway가 이미지·OCR 테이블까지 포함한 통합 `V1__baseline.sql`과 일일 한도용 V2를 빈 DB에 적용한다. 통합 V1을 사용 중인 DB에는 V2만 추가 적용한다.

```sh
docker compose up -d --wait postgres
STORAGE_TYPE=local ./gradlew bootRun --args='--spring.profiles.active=local,ocr-demo'
```

`ocr-demo` 프로필은 `app.ocr.enabled=true`, `image-source=storage`, `provider=fake`, `demo-enabled=true`를 설정한다. 기본 실행은 OCR 비활성 상태다. `prod`에서는 OCR을 켠 상태로 fake provider/image-source 또는 demo-enabled를 설정하면 시작이 거부된다. 가짜 영수증 생성 API는 `ocr-demo` 프로필에서만 등록된다.

먼저 기존 회원가입·로그인 및 방 생성 API로 토큰과 방을 준비한다. 아래 TOKEN, ROOM_ID, RECEIPT_ID, JOB_ID, IMAGE_ID는 실제 응답 값으로 바꾼다. 테스트 데이터는 실제 DB에 남으므로 별도 개발용 방을 사용한다.

### 1. 이미지 파일 없이 테스트 영수증 생성

```sh
curl -X POST http://localhost:8080/api/rooms/ROOM_ID/ocr-demo/receipts \
  -H 'Authorization: Bearer TOKEN' -H 'Content-Type: application/json' \
  -d '{"name":"OCR 테스트","scenarios":["NORMAL","MISSING_VALUES"]}'
```

HTTP 201, `data: {receiptId, imageIds, synthetic: true}`. 1~3개의 시나리오를 선택한다. 내장된 작은 PNG에 테스트 시나리오 표시를 붙여 PR 23의 업로드 서비스를 호출한다. 실제 로컬 파일 또는 S3 객체와 DB 이미지 행을 생성하고 로그인 사용자를 생성자·기본 결제자로 기록한다. 영수증/방 삭제 시 객체도 정리된다. `IMAGE_UNAVAILABLE`은 생성 직후 객체만 삭제해서 실제 저장소 읽기 실패를 재현한다.

| 시나리오 | 결과 |
|---|---|
| `NORMAL` | 8,000원 × 2 + 1,000원 × 2, 합계 18,000원 |
| `MISSING_VALUES` | 수량·단가 누락. `null` 유지, 검토 사유 제공 |
| `TOTAL_MISMATCH` | 품목 합계와 인식 총액 불일치 경고 |
| `RECOGNITION_FAILED` | `inferResult=FAILURE`, 재시도 불가 |
| `MALFORMED_RESPONSE` | 잘못된 JSON, `PROVIDER_ERROR` |
| `TIMEOUT` | 매번 타임아웃, 재시도/한도 검증용 |
| `IMAGE_UNAVAILABLE` | 저장 후 객체를 삭제해 실제 저장소 읽기 실패 재현 |

실제 JPEG·PNG가 있으면 기존 업로드 API를 바로 사용해도 된다.

```sh
curl -X POST http://localhost:8080/api/rooms/ROOM_ID/receipts/image \
  -H 'Authorization: Bearer TOKEN' -F 'file=@receipt.png' -F 'name=영수증'
```

응답 `data.receiptId`로 인식을 시작한다. 추가 사진은 `POST .../receipts/RECEIPT_ID/images`로 올린다. 일반 업로드 이미지는 가짜 CLOVA가 항상 `NORMAL` 결과(18,000원)를 반환한다. 사진 내용을 실제로 읽어낸 금액이 아니며 응답의 `provider=FAKE_CLOVA_RECEIPT`로 구분한다. OCR 작업이 생성된 후에는 성공·실패 상태와 무관하게 이미지 추가가 409로 거부된다.

### 2. 인식 시작

```sh
curl -X POST http://localhost:8080/api/rooms/ROOM_ID/receipts/RECEIPT_ID/ocr/jobs \
  -H 'Authorization: Bearer TOKEN' -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-001"}'
```

HTTP 202. DB 커밋 후 비동기 작업을 시작한다. 같은 영수증에 같은 `requestId`를 재전송하면 기존 작업을 반환하며 다른 식별자로 다시 시작하면 409 `JOB_ALREADY_EXISTS`다. 수기 영수증은 인식 대상이 아니다. 생성자가 없는 기존 이미지 영수증은 최초 요청자에게 소유권을 자동 부여하지 않으며 인식 시작을 거부한다.

### 3. 작업·초안 조회

```sh
curl http://localhost:8080/api/rooms/ROOM_ID/receipts/RECEIPT_ID/ocr/jobs/latest \
  -H 'Authorization: Bearer TOKEN'
```

`GET .../ocr/jobs/JOB_ID`도 지원한다. 방 멤버는 조회할 수 있고 시작·재시도는 현재도 방 멤버인 생성자만 가능하다. 공유 토큰은 사용할 수 없다. 아직 작업이 없으면 `latest`의 `data`는 `null`이다.

상태는 `PENDING → PROCESSING → REVIEWABLE / PARTIALLY_FAILED / FAILED`. 응답은 `jobId`, `receiptId`, `status`, `provider`, 사진별 상태·오류·시도 횟수·후보, `draft`를 제공한다. `FAKE_CLOVA_RECEIPT` 표기로 합성 결과임을 구분한다. 초안은 `EDITING`, 품목마다 원문·변환값·검토 사유를 제공한다. 단가 계산 후보는 `ocr.derivedUnitPrice`에만 표시하며 누락 단가를 자동 채우지 않는다. `itemsTotal`이 JS 안전 정수 범위를 넘으면 `null`과 합계 상한 차단 사유를 반환한다.

`applicable` 대신 초안 `status`와 `blockers`를 확인한다. `EDITING` 상태이고 `blockers`가 비어 있을 때 저장된 revision에 대한 최종 검토 확인 후 반영을 요청할 수 있다. 초안의 `blockers`는 기존 정산 결과의 `issues`와 별개다. 총액 불일치는 초안의 `OCR_TOTAL_MISMATCH` 경고다. `itemsTotal`은 삭제하지 않은 초안 행 중 단가·수량이 있는 행의 합계이며 정산 제외 품목도 포함한다. 제외 선택만으로는 불일치가 생기지 않고 삭제한 행은 합계에서 빠진다. OCR의 품목 누락·오인식, 수량·가격·할인 인식 오류 또는 사용자의 초안 추가·수정·삭제로 총액 후보와 차이가 생길 수 있다. `OCR_TOTAL_MISMATCH`는 확인을 돕는 경고이며 반영·정산을 차단하지 않는다.

### 4. 실패 사진 재시도

```sh
curl -X POST http://localhost:8080/api/rooms/ROOM_ID/receipts/RECEIPT_ID/ocr/jobs/JOB_ID/images/IMAGE_ID/retry \
  -H 'Authorization: Bearer TOKEN'
```

HTTP 202. 성공 사진은 재실행하지 않는다. 중복 클릭으로 이미 대기/처리 중이면 현재 작업을 반환한다. 재시도 불가능한 오류는 409, 이미지별 최초 1회 + 재시도 최대 2회, 총 3회 소진 후에는 429 `OCR_ATTEMPT_LIMIT`. 개발용 상한은 `app.ocr.max-attempts`로 설정한다. TIMEOUT 등 합성 실패는 재시도해도 의도적으로 같은 실패를 반환한다.

서버 재시작 또는 3분 이상 중단된 작업은 `INTERRUPTED`로 정리한다. 인식 중 영수증·방이 삭제되면 결과를 버리고, 생성자가 방에서 제거되면 `ACCESS_REVOKED`로 저장을 거부한다. 오래된 시도의 응답이 재시도 결과를 덮어쓰지 않도록 시도 번호를 검사한다. 이 실행기는 최종 설계와 같이 **단일 서버 인스턴스** 전제다.

## 호출 한도

설정은 `app.ocr.limit.per-receipt-daily`(환경변수 `OCR_PER_RECEIPT_DAILY_LIMIT`, 기본 15), `app.ocr.limit.per-user-daily`(`OCR_PER_USER_DAILY_LIMIT`, 기본 60)다.

사진 한 장의 최초 시도와 재시도를 합쳐 최대 3회(최초 1회 + 재시도 최대 2회)이며 날짜가 바뀌어도 초기화하지 않는다. 재시도 가능한 오류로 실패한 사진만 다시 호출하고 성공한 사진은 재호출하지 않는다. 일일 한도는 공급자에 보내는 사진당 1회로 계산해 영수증당 15회, 요청 사용자당 60회이며 한국 시간(`Asia/Seoul`) 자정에 새 날짜로 집계한다. 같은 시작 요청이나 이미 대기·처리 중인 재시도 요청의 중복 전송에는 추가 차감하지 않는다.

여러 사진의 필요 횟수를 DB에서 한 번에 예약하고 부족하면 전체 요청을 HTTP 429로 거부한다. 사유는 `OCR_RECEIPT_DAILY_LIMIT` 또는 `OCR_USER_DAILY_LIMIT`이다. 공급자를 호출하기 전에 실패한 사진은 최초 예약 날짜의 횟수를 반환하며, 공급자 호출 후 실패(공급자가 반환한 `UNSUPPORTED_INPUT` 포함)는 사용량에 남는다. 호출 직전 전달 상태를 기록한 이후의 서버 중단도 보수적으로 사용량에 포함한다. DB 사용량은 영수증·작업 삭제나 서버 재시작으로 초기화되지 않는다.

현재 영수증당 최대 3장 × 사진당 최대 3회이므로 한 영수증에서 도달 가능한 누적 시도는 최대 9회다. 일일 15회는 추가 안전장치이며 기본 흐름에서는 도달하지 않는다. 영수증 일일 한도 테스트는 설정값을 낮추거나 사용량 예약 계층을 직접 검증한다. 서비스 전체 일일 상한과 별도 상태 조회 제한은 설계 범위로 남아 있다.

## 팀원 연결 규약

### 저장소 연결 완료

`StoredReceiptImageReader`가 팀원의 `StorageService.read(storedPath)`를 호출한다. 업로드·읽기·삭제가 모두 같은 저장소 Bean을 사용한다. 가짜 이미지 저장소 구현체는 제거했다.

- `STORAGE_TYPE=local`(기본): 실제 로컬 파일에 저장. AWS 설정 불필요.
- `STORAGE_TYPE=s3`: PR 23의 `S3StorageService` 사용. `S3_BUCKET`과 `AWS_REGION`(기본 `ap-northeast-2`) 설정, SDK 기본 자격 증명 체인으로 인증한다.
- 실제 AWS 인증은 실행 환경의 IAM 역할, 로컬 AWS 프로필 또는 환경 변수로 설정한다. Secret Key를 코드·문서·채팅에 적을 필요가 없다. 해당 버킷 경로에 PutObject/GetObject/DeleteObject 권한이 필요하다.
- Docker 실행에서는 호스트의 AWS 프로필이 컨테이너에 자동 전달되지 않는다. 실행 환경에 적합한 자격 증명 제공이 별도로 필요하다.
- 기존 로컬 객체는 `STORAGE_TYPE`만 S3로 바꿔도 이동하지 않는다. 저장소 전환 시 객체를 같은 키로 복사해야 하며 삭제 재시도 큐도 저장소별로 분리·확인해야 한다.

실제 S3로 가짜 OCR을 검증할 때(개발용 버킷, AWS 인증이 설정된 셸):

```sh
STORAGE_TYPE=s3 S3_BUCKET=your-dev-bucket AWS_REGION=ap-northeast-2 \
  ./gradlew bootRun --args='--spring.profiles.active=local,ocr-demo'
```

### PR 23과의 충돌 정리

| 부분 | 통합 결과 |
|---|---|
| Flyway 버전 | 개발 DB 초기화에 맞춰 기존 V1~V4의 최종 구조를 `V1__baseline.sql`로 통합. 이후 변경은 V2부터 추가 |
| 생성자·이미지 엔티티 | PR 23의 `Receipt.createdBy` 관계, `ReceiptImage.create(..., StoredFile)`, 이미지 조회·삭제 경로로 통일. 생성자는 변경 불가 |
| 업로드 응답 | PR 23의 `image`, `payerMemberId`, `createdByUserId` 계약 유지. 객체 키는 API에 노출하지 않음 |
| 인식 이후 이미지 추가 | OCR 시작과 같은 방 잠금 아래 작업 존재 여부 검사. 저장소 쓰기 전 409 반환 |
| 삭제 | PR 23이 이미지 객체 정리를 예약하고 이미지 행을 삭제. OCR 테이블 FK가 후보·작업·초안을 함께 정리 |
| 기존 영수증 | 생성자 NULL은 보존. PR 23대로 방 멤버가 이미지 추가 가능하지만, OCR 소유권은 임의로 부여하지 않아 시작은 거부 |

기존 V1~V4 이력이 남은 개발 DB는 **초기화 후 실행**해야 한다. `baseline-on-migrate=false`이며 기존 DB를 자동 변환하지 않는다. 팀원도 동일하게 초기화해야 한다. DB 볼륨만 지우는 명령과 이미지 파일 보존에 관한 설명은 [README의 DB 초기화 절차](../../README.md#이전-개발-db-초기화)를 따른다.

### CLOVA 담당

`HttpClovaTransport`가 `ClovaTransport`를 구현하고 `OcrConfiguration`이 실제 엔진을 등록한다. 공통 파서·상태 관리·DB 저장을 재사용하며 HTTP timeout/429/auth 오류는 `OcrEngineException`으로 변환한다. 공급자 호출 동안 DB 트랜잭션은 열려 있지 않다.

실제 연결 시 `ocr-demo`를 끄고 `ocr` 프로필을 사용한다. URL/Secret은 시작 시 검증한다. 데모 API로 생성한 영수증과 이전 가짜 OCR 결과를 실제 인식 결과로 사용하지 말고, 새 영수증 이미지를 업로드해 검증한다.

## 검증

```sh
./gradlew test --tests '*Clova*' --tests '*Ocr*'
./gradlew test
```

H2 테스트는 실제 인증·요청·비동기 처리·DB 저장과 권한/경로/중복/실패/삭제를 검사한다. PostgreSQL Testcontainers 테스트는 Docker가 필요하며 빈 DB의 통합 V1 기동·검증, 금액/관계 제약, 반복 실행, 기존 DB 자동 baseline 차단과 삭제를 검사한다. `OcrS3IntegrationTest`는 LocalStack을 사용해 실제 S3 구현체의 3장 저장·읽기→OCR 초안→영수증/방 삭제와 객체 없음 오류를 검사한다. 합성 응답은 실제 인식 정확도나 AWS/CLOVA 통신 성공을 증명하지 않는다.
