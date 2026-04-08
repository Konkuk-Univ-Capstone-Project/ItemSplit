# Step 2 Report

## 목적

공통 응답/에러 규격을 도입하고, 전역 예외 처리와 Validation 실패 응답을 통일한다.

## 적용 내용

- 공통 응답 DTO 추가
  - `ApiResponse<T>`: 모든 성공/실패 응답의 바깥 구조를 통일하기 위한 래퍼이다. 클라이언트는 항상 `success`, `data`, `error` 위치를 동일하게 기대할 수 있다.
  - `ErrorResponse`: 실패 응답에서 사용할 에러 본문이다. 에러 코드, 사용자에게 보여줄 메시지, 필드 단위 검증 실패 목록을 함께 담는다.
- 기본 에러코드 정의
  - `UNAUTHORIZED`: 인증이 필요한 요청에 인증 정보가 없거나 유효하지 않을 때 사용한다.
  - `FORBIDDEN`: 로그인은 되었지만 해당 리소스에 접근 권한이 없을 때 사용한다.
  - `NOT_FOUND`: 요청한 리소스가 존재하지 않을 때 사용한다.
  - `VALIDATION_ERROR`: 요청 형식이나 필드 값이 제약 조건을 만족하지 않을 때 사용한다.
  - `INTERNAL_ERROR`: 위에서 분류하지 못한 예외가 발생했을 때 사용하는 최종 안전망 코드이다.
- 커스텀 예외 추가
  - `ApiException`: 서비스나 컨트롤러에서 의도적으로 비즈니스 에러를 던질 때 사용할 공통 예외이다. `ErrorCode`와 메시지를 함께 넘겨 응답 규격으로 바로 연결할 수 있다.
- 전역 예외 처리기 추가
  - `@RestControllerAdvice`: 컨트롤러 전반에서 발생하는 예외를 한곳에서 처리해 응답 포맷을 강제로 통일한다.
  - `MethodArgumentNotValidException`: `@Valid` 기반 요청 DTO 검증 실패를 잡아 필드별 상세 에러 목록으로 변환한다.
  - `ValidationException`: 서비스 레벨 검증 실패를 공통 `VALIDATION_ERROR` 응답으로 변환한다.
  - `AuthenticationException`: 인증 실패를 `401 UNAUTHORIZED` 규격으로 변환한다.
  - `AccessDeniedException`: 인가 실패를 `403 FORBIDDEN` 규격으로 변환한다.
  - `NoSuchElementException`: 조회 대상이 없을 때 `404 NOT_FOUND`로 변환한다.
  - `NoResourceFoundException`: 잘못된 URL 요청 같은 리소스 미존재 상황도 통일된 `404` 포맷으로 내려가도록 처리한다.
  - 기타 `Exception`: 예상하지 못한 예외는 내부 구현을 노출하지 않고 `500 INTERNAL_ERROR`로 감싼다.
- 샘플 API 추가
  - `GET /api/ping`: 가장 단순한 성공 응답 예시이다. 공통 응답 포맷이 정상 동작하는지 빠르게 확인할 수 있다.
  - `POST /api/ping/echo`: 요청 본문 검증과 성공 응답을 함께 확인하기 위한 예시 API이다. 빈 문자열이나 길이 제한 위반 시 Validation 응답 포맷을 바로 볼 수 있다.
- 샘플 API 확인용 임시 보안 설정 추가
  - 현재는 전체 요청 `permitAll`로 열어 두었다. Step 2의 핵심은 응답 규격 검증이므로, Spring Security 기본 로그인 화면이나 기본 인증이 샘플 API 확인을 방해하지 않도록 최소 설정만 적용했다.
  - Step 3에서 JWT 기반 `SecurityFilterChain`으로 교체하면서 인증/인가 정책을 다시 적용할 예정이다.
- MVC 테스트 추가
  - 성공 응답 포맷 테스트: `GET /api/ping`이 공통 성공 구조를 반환하는지 검증한다.
  - Validation 에러 포맷 테스트: `POST /api/ping/echo`가 필드 오류를 통일된 실패 구조로 반환하는지 검증한다.
  - 예외 매핑 테스트: `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_ERROR`, `INTERNAL_ERROR`가 의도한 HTTP 상태와 JSON 구조로 내려가는지 검증한다.

## 검증 결과

- `./gradlew test` 성공
  - `BUILD SUCCESSFUL in 4s`
  - 팀원이 올린 JPA 엔티티 변경을 `origin/main`에서 반영한 뒤에도 동일하게 통과함을 확인했다.
