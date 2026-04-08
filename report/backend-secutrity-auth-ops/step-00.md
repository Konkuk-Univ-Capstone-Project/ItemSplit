# Step 0 Report

## 날짜

2026-04-01

## 목적

현재 레포 구조와 실행 기반 상태를 읽기 전용으로 점검한다.

## 확인 결과

- Spring Boot `3.5.13`, Java `21`, Gradle Wrapper `8.14.4`
- 설정 파일은 `src/main/resources/application.properties`만 존재
- Actuator 의존성은 포함되어 있으나 엔드포인트 노출 설정은 없음
- Testcontainers 기반 PostgreSQL 테스트 설정 존재
- `README.md`는 매우 간단한 상태로 실행 방법 부재
- `.gitattributes`는 일부만 설정되어 있고 기본 LF 규칙은 없음

## Step 1 계획

- Docker Compose 기반 로컬 PostgreSQL 실행 환경 추가
- `local`/`prod` 프로파일 분리
- `.env.example`, `.gitignore`, `.gitattributes`, `README.md` 정리
- `/actuator/health` 확인 가능한 최소 운영 기반 구성
