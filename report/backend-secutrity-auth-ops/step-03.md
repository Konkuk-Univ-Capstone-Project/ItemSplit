# Step 3 Report

## 목적

JWT 기반 자체 로그인 기능을 도입하고, 회원가입/로그인/토큰 검증/인증 필요 API의 401 응답까지 한 흐름으로 연결한다.

## 적용 내용

- `User` 저장/조회 기반 정리
  - `UserRepository`: 이메일 중복 확인과 이메일 기반 사용자 조회를 담당한다. 회원가입/로그인의 핵심 조회 경로이다.
  - `UserService`: 사용자 생성과 조회를 서비스 계층으로 분리했다. 컨트롤러나 인증 로직이 직접 JPA 접근 세부사항을 몰라도 되게 한다.
  - `User.create(...)`: 회원가입 시 엔티티를 명확하게 생성하기 위한 정적 팩토리 메서드이다.

- 회원가입/로그인 API 추가
  - `POST /api/auth/signup`: 이메일, 비밀번호, 닉네임을 받아 새 사용자를 생성한다. 비밀번호는 저장 전에 BCrypt로 해싱된다.
  - `POST /api/auth/login`: 이메일과 비밀번호를 검증하고, 성공 시 JWT access token을 발급한다.
  - `GET /api/auth/me`: 현재 토큰으로 인증된 사용자 정보를 반환하는 보호 API이다. JWT 필터가 실제로 동작하는지 확인하는 기준 엔드포인트로도 사용한다.

- 인증 서비스 계층 추가
  - `AuthService`: 회원가입과 로그인 규칙을 한곳에 모았다.
  - 회원가입 시 이메일을 소문자로 정규화해 중복 가능성을 줄였다.
  - 로그인 시 저장된 BCrypt 해시와 입력 비밀번호를 비교해 인증을 수행한다.
  - 이메일 중복은 `VALIDATION_ERROR`, 잘못된 로그인 정보는 `UNAUTHORIZED` 응답으로 연결되도록 했다.

- JWT 발급/검증 로직 추가
  - `JwtTokenProvider`: JWT 생성과 파싱을 담당한다.
  - 토큰에는 사용자 `id`, `email`, `nickname`을 담아 이후 요청에서 인증 주체를 복원할 수 있게 했다.
  - `JWT_SECRET` 환경변수를 사용해 서명 키를 주입하고, 기본 만료시간은 `app.jwt.access-token-expiration` 값으로 관리한다.

- Spring Security를 JWT 방식으로 전환
  - `SecurityConfig`: 세션 기반이 아닌 stateless 보안 체인으로 변경했다.
  - 공개 엔드포인트는 `signup`, `login`, `ping`, actuator `health/info`만 허용하고, 나머지는 인증이 필요하도록 설정했다.
  - `PasswordEncoder`로 `BCryptPasswordEncoder`를 등록해 인증 서비스에서 공통 사용한다.

- JWT 인증 필터 및 보안 에러 응답 추가
  - `JwtAuthenticationFilter`: `Authorization: Bearer ...` 헤더를 읽어 토큰을 검증하고 `SecurityContext`에 인증 정보를 넣는다.
  - `AuthenticatedUser`: 필터가 인증된 사용자 정보를 컨트롤러까지 전달하기 위한 경량 principal 객체이다.
  - `RestAuthenticationEntryPoint`: 토큰이 없거나 잘못된 경우 공통 응답 포맷의 `401 UNAUTHORIZED` JSON을 내려준다.
  - `RestAccessDeniedHandler`: 인증은 되었지만 권한이 부족한 경우 공통 포맷의 `403 FORBIDDEN` JSON을 내려준다.

- 테스트 전략 정리
  - `AuthControllerTest`: 회원가입 성공/중복 실패, 로그인 성공/실패, 보호 API의 401/인증 성공을 통합 테스트한다.
  - `application-test.yml`: 테스트는 H2 메모리 DB로 실행되게 구성했다. Docker가 없는 환경에서도 `./gradlew test`가 안정적으로 돌도록 한 조치이다.
  - `PingControllerTest`: Step 2의 응답/예외 테스트가 Step 3 보안 필터에 끌려가지 않도록 MVC 슬라이스 테스트에서 JWT 필터만 mock 처리했다.

## 검증 결과

- `./gradlew test` 성공
  - `BUILD SUCCESSFUL in 4s`
- 확인된 주요 시나리오
  - 회원가입 성공 시 BCrypt 해시 저장
  - 중복 이메일 회원가입 실패
  - 로그인 성공 시 JWT 발급
  - 로그인 실패 시 `401 UNAUTHORIZED`
  - 보호 API 무토큰 요청 시 `401 UNAUTHORIZED`
  - 유효 토큰 요청 시 현재 사용자 정보 반환
