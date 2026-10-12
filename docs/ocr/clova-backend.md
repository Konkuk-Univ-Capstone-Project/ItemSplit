# 실제 CLOVA 영수증 OCR 실행

`이미지 업로드 → local/S3 읽기 → CLOVA Document 영수증 API → DB 초안 저장 → 검토·수정 → 정산 품목 반영 → 정산 결과`를 지원한다. 공급자는 `CLOVA_RECEIPT`로 표시된다.

## 설정과 실행

프로젝트 루트 `.env`에 아래 값을 입력한다. 로컬 `ocr` 프로필은 이 파일을 Spring properties 형식으로 읽으므로 값에 따옴표를 붙이지 않는다. 실제 환경변수가 있으면 그 값이 우선한다. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외된다.

```dotenv
CLOVA_OCR_INVOKE_URL=https://발급받은-주소/document/receipt
CLOVA_OCR_SECRET=발급받은-Secret-Key
```

URL은 콘솔의 **APIGW Invoke URL 전체**를 복사한다. HTTPS와 `/receipt`로 끝나는 경로를 검증하며 URL을 임의로 조립하지 않는다. 프론트엔드에는 URL·키를 넣지 않는다.

로컬 Java 21 실행:

```sh
docker compose up -d --wait postgres
./gradlew bootRun --args='--spring.profiles.active=local,ocr'
```

Docker 실행:

```sh
SPRING_PROFILES_ACTIVE=local,ocr docker compose up -d --build app
```

계속 Docker로 실제 OCR을 사용하려면 `.env`의 `SPRING_PROFILES_ACTIVE=local,ocr`를 설정한 뒤 `docker compose up -d --build app`을 실행해도 된다. 운영에서는 `prod,ocr`와 서버 환경변수를 사용한다. `bootRun`은 위와 같이 프로필 인자를 명시한다.

`ocr-demo`는 가짜 응답용이며 `ocr`와 함께 활성화할 수 없다. 기본 `local`만 실행하면 기존처럼 OCR은 꺼져 있다. 실제 OCR을 켰는데 URL·키가 없거나 잘못된 경우 시작을 거부한다. 가짜 공급자로 대체하지 않는다.

## 호출과 실패 처리

웹에서는 방을 선택한 뒤 **영수증 사진 첨부 → 사진 업로드 → 영수증 인식 시작** 순서로 사용한다. 사진 첨부는 JPEG·PNG 한 장을 지원한다. 업로드와 인식 시작은 별도 요청이며, 인식 상태는 GET으로 조회한다. 새로고침하거나 영수증을 다시 선택해도 저장된 최신 작업을 조회할 뿐 인식을 자동 재실행하지 않는다.

완료 화면에서 품목명·단가·수량, 참여자, 정산 제외·삭제 표시를 수정하고 누락 품목을 추가할 수 있다. **검토 내용 저장 → 검토 확인 체크 → 정산에 반영 → 정산 탭** 순서로 진행한다. 아래 상세 영역에서 결제자를 바꿀 수 있다. 실패한 사진은 품목을 수동 입력한 뒤 확인 체크를 저장해야 반영할 수 있다. 실패 사진 재시도는 API만 제공하며, 이미 반영한 초안은 다시 인식하거나 반영하지 않는다.

로컬 웹 개발 서버는 `frontend` 폴더에서 `npm run dev`로 실행한다. 백엔드가 기본 8080 외의 포트라면 `VITE_API_PROXY_TARGET=http://localhost:18080 npm run dev`처럼 지정한다.

[기존 업로드·OCR 시작·조회 API](mock-backend.md#2-인식-시작)를 사용한다. 사진 업로드 후 OCR 시작 요청을 해야 인식이 실행된다. 저장된 사진 한 장마다 `X-OCR-SECRET` 헤더, V2 `message`와 `file`을 multipart로 전송한다. 앱 시작이나 상태 조회는 CLOVA를 호출하지 않는다.

- 연결 제한 기본 3초, 읽기 제한 기본 15초. `.env` 또는 환경변수 `OCR_CONNECT_TIMEOUT`, `OCR_READ_TIMEOUT`으로 변경한다.
- 자동 재시도와 HTTP 리다이렉트 추적은 하지 않는다. 기존 실패 사진 수동 재시도만 허용하며 사진별 최대 시도 횟수는 3회다.
- 401/403/404 및 기타 설정 오류: `CONFIG_ERROR`, 재시도 불가.
- 400/413/415/422: `UNSUPPORTED_INPUT`, 재시도 불가.
- 408/504·통신 시간 초과: `TIMEOUT`, 429: `RATE_LIMITED`, 서버·통신 오류: `PROVIDER_ERROR`. 수동 재시도 가능.
- JPEG·PNG 한 장, 최대 10 MiB. 응답은 최대 2 MiB까지 읽으며 `inferResult`도 검사한다.
- 인증 키·호출 URL·이미지·공급자 응답 전체를 오류나 로그에 남기지 않는다. DB에는 기존 파서가 선택한 검토 필드만 저장한다.

## 초안 저장·반영 계약

- `PUT /api/rooms/{roomId}/receipts/{receiptId}/ocr/jobs/{jobId}/draft`: `revision`, `coveredImageIds`, `items` 전체 목록을 보낸다. 행은 `draftItemId`(새 수기 행은 null), `name`, `price`, `quantity`, `memberIds`, `excludedFromSettlement`, `removed`를 포함한다. OCR 원문은 변경하지 않는다. 원래 OCR 행은 목록에서 지우는 대신 `removed=true`로 보낸다.
- `POST .../{jobId}/draft/apply`: `{ "revision": 2, "reviewConfirmed": true }`. 서버에 저장된 해당 버전만 반영한다. 방 쓰기 잠금 안에서 실제 `items`·`assignments`를 추가하고 초안 상태를 함께 확정한다. 동일 반영 revision 재요청은 현재 영수증을 반환하며 중복 생성하지 않는다.
- 작성자만 저장·반영할 수 있다. 인식 진행 중, 오래된 revision, 미해결 초안 오류·실패 사진, 기존 품목을 포함한 영수증/방 합계 한도 초과는 차단한다. 기존 품목 및 이름·결제자·원본 총액·날짜는 덮어쓰지 않는다.
- 반영된 OCR 품목의 이름·단가·수량·삭제는 작성자만 변경할 수 있다. 참여자·정산 제외·영수증 메타 변경은 기존 방 멤버 정책을 유지한다.
- 반영된 OCR 영수증은 확정한 품목 합계로 정산한다. OCR 총액 불일치는 검토 경고이며 원본 `declaredTotal` 미입력 때문에 정산을 막지 않는다. 기존 수기 영수증의 총액 검증은 유지한다. 참여자·결제자가 없으면 정산 준비 사유를 표시한다.
- 단일 V1 기준선에 반영 이력, 초안 참여자, 실패 사진 수동 확인, 실제 품목의 고유 초안 출처를 포함한다. 빈 DB에서 생성하며, 이전 V1 또는 V1+V2 적용 DB는 백업 후 새 기준선으로 데이터를 이관해야 한다.



## 업로드한 사진 원본 보기

이미지 영수증을 선택하면 저장된 원본 사진과 OCR 품목을 함께 표시한다. 썸네일에서 사진을 선택하고 100~400%로 확대·축소할 수 있다. 품목의 ‘원본 보기’를 누르면 해당 사진과 인식 영역으로 이동한다. OCR 좌표가 없으면 전체 사진을 표시한다. 모바일에서는 사진·품목 탭으로 전환하며 편집 입력값은 유지된다.

`GET /api/rooms/{roomId}/receipts/{receiptId}/images`는 JWT와 방 멤버 권한을 검사하고 `imageId`, `order`, `url`, `expiresAt`, `contentType` 목록을 반환한다. 로컬은 5분 HMAC 서명 URL, S3는 5분 presigned GET을 사용한다. 공유 토큰으로 이미지 목록을 조회할 수 없다. URL 만료 시 목록만 갱신하며 OCR 재호출이나 초안 재조회는 하지 않는다. 이미지 오류의 자동 갱신은 한 번으로 제한하고 실패 후에는 명시적으로 다시 불러오게 한다.

로컬 서명 키는 `LOCAL_STORAGE_SIGNING_SECRET`(32바이트 이상)이며 JWT 키와 분리한다. 미설정 개발 환경에서는 시작 시 임의 키를 생성하므로 재시작 전에 발급한 URL은 무효화된다. 이미지 목록 및 로컬 파일 응답은 캐시하지 않으며, Docker nginx는 해당 파일 경로의 서명 URL을 로그에 남기지 않는다.


## 수동 품목 보완

OCR 반영 전에는 `정산 품목 검토 → 누락 품목 추가 → 검토 내용 저장 → 정산에 반영`으로 초안에 행을 추가한다. 반영 후에는 인식 결과의 `누락 품목 추가`로 영수증 상세의 수동 입력 폼으로 이동한다. 이 폼은 기존 `POST /api/rooms/{roomId}/receipts/{receiptId}/items`를 사용해 같은 영수증에 품목을 추가하며, 저장 후 상세 목록에서 참여자를 지정한다. OCR 초안이나 기존 정산 품목을 덮어쓰거나 재인식하지 않는다.

영수증 목록은 멤버 영역 아래, 입력·편집 화면 위의 고정된 순서로 표시한다. 목록 오른쪽 `+`를 누르면 아래에 새 작성 화면이 열리고 `영수증 사진 첨부`가 기본으로 선택된다. `수동입력` 버튼으로 입력 방식을 바꿀 수 있으며 방식 전환 중 입력값은 유지한다. 기존 영수증을 선택하면 입력 방식 선택 대신 해당 영수증의 OCR 검토·수정·상세를 목록 아래에 표시한다.

일반 사진 보기와 영수증 목록은 페이지 스크롤을 사용한다. 확대된 사진만 별도 스크롤을 사용하며, 좁은 화면에서도 입력·편집 패널을 세로로 배치한다.


## 수동 영수증 정산

수동 영수증은 총액을 입력하지 않으면 현재 정산 대상 품목 합계로 정산한다. 직접 입력한 총액은 제외 품목을 포함한 전체 품목 합계와 비교한다. 정산 입력 오류에는 영수증 목록과 같은 이름을 표시한다.
