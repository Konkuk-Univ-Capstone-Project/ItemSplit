package com.capstone.itemsplit.common.response;

import com.capstone.itemsplit.common.exception.ErrorCode;
import java.util.List;

public record ErrorResponse(String code, String message, List<FieldErrorDetail> details) {

	public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldErrorDetail> details) {
		return new ErrorResponse(errorCode.name(), message, details);
	}

	public record FieldErrorDetail(String field, String rejectedValue, String reason) {
	}

}
