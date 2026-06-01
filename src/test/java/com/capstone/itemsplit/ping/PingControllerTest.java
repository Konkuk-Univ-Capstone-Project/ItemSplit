package com.capstone.itemsplit.ping;

import com.capstone.itemsplit.auth.JwtAuthenticationFilter;
import com.capstone.itemsplit.common.exception.GlobalExceptionHandler;
import com.capstone.itemsplit.common.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = {PingController.class, TestErrorController.class})
@Import(GlobalExceptionHandler.class)
class PingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@MockitoBean
	private RateLimitProperties rateLimitProperties;

	@Test
	@DisplayName("GET /api/ping 요청은 통합 성공 응답 형식을 반환한다")
	void 핑_요청은_통합_성공_응답을_반환한다() throws Exception {
		mockMvc
			.perform(get("/api/ping"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.message").value("pong"))
			.andExpect(jsonPath("$.error").isEmpty());
	}

	@Test
	@DisplayName("POST /api/ping/echo 요청은 검증 오류를 통합 실패 응답 형식으로 반환한다")
	void 에코_요청은_검증_실패_응답을_반환한다() throws Exception {
		mockMvc
			.perform(
				post("/api/ping/echo")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "message": ""
						}
						""")
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").isEmpty())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.details[0].field").value("message"))
			.andExpect(jsonPath("$.error.details[0].reason").value("message must not be blank"));
	}

	@Test
	@DisplayName("잘못된 JSON 요청은 VALIDATION_ERROR 응답으로 변환된다")
	void 잘못된_JSON_요청은_VALIDATION_ERROR_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(
				post("/api/ping/echo")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{")
			)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Request body is missing or malformed."));
	}

	@Test
	@DisplayName("ApiException은 통합 NOT_FOUND 응답으로 변환된다")
	void API_예외는_통합_NOT_FOUND_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Ping target was not found."));
	}

	@Test
	@DisplayName("AuthenticationException은 통합 UNAUTHORIZED 응답으로 변환된다")
	void 인증_예외는_통합_UNAUTHORIZED_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/unauthorized"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value("Authentication is required."));
	}

	@Test
	@DisplayName("AccessDeniedException은 통합 FORBIDDEN 응답으로 변환된다")
	void 접근_거부_예외는_통합_FORBIDDEN_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/forbidden"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("Ping access is forbidden."));
	}

	@Test
	@DisplayName("ValidationException은 통합 VALIDATION_ERROR 응답으로 변환된다")
	void 검증_예외는_통합_VALIDATION_ERROR_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/validation"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Invalid ping request."));
	}

	@Test
	@DisplayName("NoSuchElementException은 통합 NOT_FOUND 응답으로 변환된다")
	void 요소_없음_예외는_통합_NOT_FOUND_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/missing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Ping target was not found."));
	}

	@Test
	@DisplayName("처리되지 않은 예외는 통합 INTERNAL_ERROR 응답으로 변환된다")
	void 처리되지_않은_예외는_통합_INTERNAL_ERROR_응답으로_변환된다() throws Exception {
		mockMvc
			.perform(get("/test-errors/internal"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value("An unexpected error occurred."));
	}

}
