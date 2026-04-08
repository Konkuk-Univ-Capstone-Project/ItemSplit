package com.capstone.itemsplit.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to access this resource."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "The request contains invalid values."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

}
