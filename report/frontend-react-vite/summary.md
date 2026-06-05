# React + TypeScript + Vite 프론트엔드

## Summary

`main` 브랜치에 ItemSplit 웹 프론트엔드를 추가했습니다. React + TypeScript + Vite 기반으로 로그인/회원가입, 방 관리, 멤버 관리, 영수증 수동 입력/수정, 품목별 참여자 지정, 정산 조회, 공유 토큰 발급 화면을 구현했습니다.

디자인은 메타 문서의 톤을 바탕으로 업무형 대시보드에 맞게 구성했고, Docker demo profile에서 프론트엔드도 함께 실행할 수 있도록 Nginx 배포 설정을 추가했습니다.

## 주요 변경

- `frontend/` 하위에 React + TypeScript + Vite 앱을 추가했습니다.
- API 클라이언트와 응답 타입을 분리해 backend API와 연동했습니다.
- 로그인/회원가입 화면을 구현했습니다.
  - 로그인 입력값은 빈칸으로 시작합니다.
  - 계정 생성 성공 시 자동 로그인됩니다.
- 방 목록/생성/삭제 UI를 구현했습니다.
  - 방 삭제는 owner 방에서만 노출됩니다.
- 초대 토큰 참여 흐름을 구현했습니다.
  - 초대 토큰 입력 후 바로 입장하지 않고 참여 옵션 모달을 표시합니다.
  - 수동 멤버와 자신을 매칭하거나 새 멤버로 참여할 수 있습니다.
- 멤버 관리 UI를 구현했습니다.
  - 수동 멤버 추가
  - 멤버 이름 수정
  - owner 제외 멤버 삭제
  - 자기 자신 삭제 시 방 나가기 처리
- 영수증 화면을 구현했습니다.
  - 수동 영수증 추가/수정/삭제
  - 품목 추가/삭제
  - 품목 가격/수량 기반 총액 자동 계산
  - 영수증 클릭 시 수동 입력 폼에서 기존 내역 수정
  - 결제자 선택 및 수정
  - 수동 입력 단계에서 참여자 일괄 지정
- 영수증 상세 화면을 구현했습니다.
  - 품목별 참여자 칩 클릭 즉시 저장
  - 결제자 선택 즉시 저장
  - 품목 삭제
- 정산 화면을 구현했습니다.
  - 멤버별 부담/결제/정산 금액 표시
  - 누가 누구에게 송금해야 하는지 송금 흐름 표시
- 공유 화면을 구현했습니다.
  - 초대 토큰 발급
  - 읽기 전용 공유 링크 발급
  - 토큰 발급 시 자동 복사 및 복사 버튼 제공
- 좁은 화면 대응을 위한 responsive layout을 적용했습니다.

## Docker/실행 환경

- `frontend/Dockerfile`과 `frontend/nginx.conf`를 추가했습니다.
- `docker-compose.yml`의 `demo` profile에 `frontend` 서비스를 추가했습니다.
- `.env.example`에 `APP_PORT`, `FRONTEND_PORT` 예시를 추가했습니다.
- `.gitignore`에 frontend build/dependency 산출물과 Playwright 검증 산출물을 제외하도록 추가했습니다.
- README에 frontend Docker/로컬 실행 방법을 추가했습니다.

## Backend 의존성

이 frontend는 다음 backend API 변경과 함께 동작하도록 구현되었습니다.

- `RoomMember` 기반 `memberId` 응답
- 수동 멤버 추가/수정/삭제 API
- 초대 참여 옵션 조회 API
- 수동 멤버 매칭 참여 API
- `RoomMember` 기준 결제자/참여자/정산 응답
- 방 삭제 API

관련 backend 브랜치: `backend-room-member-receipt-ops`

## Verification

```bash
cd frontend
npm run build
```

## Commits

- `fcd168d feat: React Vite 프론트엔드 워크스페이스 추가`
- `57c05f6 chore: 프론트엔드 Docker 실행 환경 추가`

