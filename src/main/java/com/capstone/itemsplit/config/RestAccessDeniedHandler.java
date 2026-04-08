package com.capstone.itemsplit.config;

import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException
	) throws IOException, ServletException {
		response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ErrorResponse errorResponse = ErrorResponse.of(
			ErrorCode.FORBIDDEN,
			accessDeniedException.getMessage(),
			List.of()
		);

		objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorResponse));
	}

}
