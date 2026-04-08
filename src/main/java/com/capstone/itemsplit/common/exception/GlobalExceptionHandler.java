package com.capstone.itemsplit.common.exception;

import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.common.response.ErrorResponse;
import jakarta.validation.ValidationException;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
		return buildErrorResponse(exception.getErrorCode(), exception.getMessage(), List.of());
	}

	@ExceptionHandler(NoSuchElementException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoSuchElementException(NoSuchElementException exception) {
		return buildErrorResponse(ErrorCode.NOT_FOUND, exception.getMessage(), List.of());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
		return buildErrorResponse(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), List.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
		MethodArgumentNotValidException exception
	) {
		List<ErrorResponse.FieldErrorDetail> details = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(this::toFieldErrorDetail)
			.toList();

		return buildErrorResponse(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(), details);
	}

	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(ValidationException exception) {
		return buildErrorResponse(ErrorCode.VALIDATION_ERROR, exception.getMessage(), List.of());
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
		return buildErrorResponse(ErrorCode.UNAUTHORIZED, exception.getMessage(), List.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
		return buildErrorResponse(ErrorCode.FORBIDDEN, exception.getMessage(), List.of());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
		return buildErrorResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), List.of());
	}

	private ResponseEntity<ApiResponse<Void>> buildErrorResponse(
		ErrorCode errorCode,
		String message,
		List<ErrorResponse.FieldErrorDetail> details
	) {
		ErrorResponse errorResponse = ErrorResponse.of(errorCode, message, details);
		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ApiResponse.failure(errorResponse));
	}

	private ErrorResponse.FieldErrorDetail toFieldErrorDetail(FieldError fieldError) {
		String rejectedValue = fieldError.getRejectedValue() == null
			? null
			: String.valueOf(fieldError.getRejectedValue());

		return new ErrorResponse.FieldErrorDetail(
			fieldError.getField(),
			rejectedValue,
			fieldError.getDefaultMessage()
		);
	}

}
