package com.capstone.itemsplit.settlement;
import com.capstone.itemsplit.common.response.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
@RestControllerAdvice @org.springframework.core.annotation.Order(-1)
public class SettlementExceptionHandler {
    @ExceptionHandler(SettlementRequestException.class)
    ResponseEntity<ApiResponse<Void>> handle(SettlementRequestException exception) {
        return ResponseEntity.status(exception.getErrorCode().getStatus()).body(ApiResponse.failure(
            ErrorResponse.of(exception.getErrorCode(),exception.getMessage(),
                List.of(new ErrorResponse.FieldErrorDetail("settlement",null,exception.reason())))));
    }
}
