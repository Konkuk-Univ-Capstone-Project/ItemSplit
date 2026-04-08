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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException, ServletException {
		String message = ErrorCode.UNAUTHORIZED.getMessage();
		if (authException instanceof BadCredentialsException) {
			message = authException.getMessage();
		}

		response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ErrorResponse errorResponse = ErrorResponse.of(
			ErrorCode.UNAUTHORIZED,
			message,
			List.of()
		);

		objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorResponse));
	}

}
