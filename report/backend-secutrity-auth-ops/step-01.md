# Step 1 Report

## 날짜

2026-04-01 ~ 2026-04-02

## 목적

로컬 실행 표준화: DB + profile + env + health 기반을 구성한다.

## 적용 내용

- `docker-compose.yml` 추가
  - PostgreSQL 16 Alpine
  - 볼륨, 포트 매핑, 헬스체크 포함
- `.env.example` 추가
  - `DB_*`, `JWT_SECRET` 예시 제공
- Spring 설정 YAML로 전환
  - `application.yml`
  - `application-local.yml`
  - `application-prod.yml`
- 공통 설정 반영
  - `spring.jpa.open-in-view=false`
  - actuator `health`, `info` 노출
- 운영 설정 반영
  - `prod` 프로파일은 env 기반 주입만 허용
  - `ddl-auto=validate`
- 문서/협업 설정 반영
  - `README.md` 실행 방법 추가
  - `.gitattributes`에 기본 LF, `*.bat` CRLF 유지
  - `.gitignore`에 `.env`, `.DS_Store`, `report/` 추가

## 검증 결과

- `docker compose config` 성공
- `./gradlew test` 성공
- `docker compose up -d` 성공
- `./gradlew bootRun` 성공
- `curl -i http://localhost:8080/actuator/health` 성공
  - `HTTP/1.1 200`
  - `{"status":"UP","groups":["liveness","readiness"]}`

## 다음 단계

- 공통 응답 포맷 `ApiResponse<T>` 도입
- 전역 예외 처리 `@RestControllerAdvice` 구성
- Validation 에러/인증 에러/기본 에러를 동일 포맷으로 통일
