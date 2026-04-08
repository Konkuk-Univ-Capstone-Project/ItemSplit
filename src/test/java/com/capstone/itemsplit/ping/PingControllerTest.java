package com.capstone.itemsplit.ping;

import com.capstone.itemsplit.auth.JwtAuthenticationFilter;
import com.capstone.itemsplit.common.exception.GlobalExceptionHandler;
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

	@Test
	@DisplayName("GET /api/ping returns the unified success response format")
	void pingReturnsSuccessResponse() throws Exception {
		mockMvc
			.perform(get("/api/ping"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.message").value("pong"))
			.andExpect(jsonPath("$.error").isEmpty());
	}

	@Test
	@DisplayName("POST /api/ping/echo returns validation errors in the unified failure response format")
	void echoReturnsValidationFailureResponse() throws Exception {
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
	@DisplayName("ApiException is converted to the unified not found response format")
	void apiExceptionReturnsNotFoundResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Ping target was not found."));
	}

	@Test
	@DisplayName("AuthenticationException is converted to the unified unauthorized response format")
	void authenticationExceptionReturnsUnauthorizedResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/unauthorized"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value("Authentication is required."));
	}

	@Test
	@DisplayName("AccessDeniedException is converted to the unified forbidden response format")
	void accessDeniedExceptionReturnsForbiddenResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/forbidden"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("Ping access is forbidden."));
	}

	@Test
	@DisplayName("ValidationException is converted to the unified validation error response format")
	void validationExceptionReturnsValidationErrorResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/validation"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Invalid ping request."));
	}

	@Test
	@DisplayName("NoSuchElementException is converted to the unified not found response format")
	void noSuchElementExceptionReturnsNotFoundResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/missing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Ping target was not found."));
	}

	@Test
	@DisplayName("Unhandled exceptions are converted to the unified internal error response format")
	void exceptionReturnsInternalErrorResponse() throws Exception {
		mockMvc
			.perform(get("/test-errors/internal"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value("An unexpected error occurred."));
	}

}
