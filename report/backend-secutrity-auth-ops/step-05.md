# Step 05 - Local Storage Abstraction

## 목표
- `StorageService` 인터페이스를 두고 저장 방식을 추상화한다.
- 1학기 MVP에서는 상대경로 `./storage`를 기본으로 사용하는 로컬 구현체를 제공한다.
- 방 멤버만 영수증 이미지를 업로드할 수 있게 하고, 업로드된 파일의 메타데이터를 DB에 함께 남긴다.
- 이후 OCR 단계에서 저장된 파일 경로와 영수증 정보를 바로 이어서 사용할 수 있게 기반을 만든다.

## 이번 단계에서 추가/보완한 내용

### 1. `StorageService`
- 파일 저장 기능을 인터페이스로 분리했다.
- 현재는 로컬 저장소를 쓰지만, 이후 S3 같은 외부 스토리지로 교체할 때 상위 서비스 코드를 크게 바꾸지 않도록 하기 위한 추상화 레이어다.
- `store(directory, file)` 호출 결과로 저장 경로, 원본 파일명, MIME 타입, 크기를 돌려주게 해서 업로드 후 DB 기록에 필요한 정보를 한 번에 넘길 수 있게 했다.

### 2. `LocalStorageService`
- `app.storage.local.root-path` 설정값을 읽어 실제 파일을 디스크에 저장하는 구현체를 추가했다.
- 기본 저장 경로는 `./storage`이므로 Windows/macOS 모두 프로젝트 루트 기준 상대경로로 같은 방식으로 사용할 수 있다.
- 저장 시 대상 디렉터리를 자동 생성하고, UUID 기반 파일명을 사용해 파일명 충돌을 피한다.
- 반환하는 저장 경로는 `/` 기준의 상대경로 문자열로 통일해서 OS별 경로 구분자 차이로 인한 혼선을 줄였다.
- 원본 파일명은 파일명만 남기도록 정리해서 경로 주입 위험을 줄였다.

### 3. 영수증 업로드 흐름
- `ReceiptService`는 방 멤버 여부를 먼저 확인한 뒤 이미지 파일만 저장하도록 검증한다.
- 업로드가 성공하면 저장소에서 받은 파일 메타데이터와 방 정보를 묶어 `Receipt` 엔티티로 저장한다.
- 이렇게 해 두면 나중에 OCR 처리 시 DB에서 영수증 레코드를 읽고 실제 파일 위치를 바로 찾을 수 있다.

### 4. 영수증 업로드 API
- `ReceiptController`는 `POST /api/rooms/{roomId}/receipts/image` 엔드포인트로 multipart 이미지를 받는다.
- 인증된 사용자만 호출할 수 있고, 실제 권한은 `RoomAuthorizationService.checkMember(roomId, userId)`로 검사한다.
- 응답에는 `receiptId`, `roomId`, `name`, `storedPath`, `originalFilename`, `contentType`, `fileSize`를 포함해서 업로드 결과를 즉시 확인할 수 있게 했다.

### 5. 테스트 보강
- 테스트 프로파일은 `./build/test-storage`를 저장 경로로 사용하게 해서 실제 로컬 `./storage`를 오염시키지 않도록 유지했다.
- `ReceiptControllerTest`에서 업로드 성공 시 파일이 디스크에 저장되고 DB 메타데이터가 남는지 확인한다.
- 비멤버 업로드가 `FORBIDDEN`으로 막히는지도 검증한다.
- 이미지가 아닌 파일 업로드는 `VALIDATION_ERROR`로 막히는 케이스도 추가했다.

## 현재 상태
- 로컬 저장소 추상화가 동작한다.
- 방 멤버 권한 체크가 적용된 이미지 업로드 API가 동작한다.
- OCR 다음 단계에서 활용할 수 있는 파일 경로와 영수증 메타데이터가 함께 저장된다.
