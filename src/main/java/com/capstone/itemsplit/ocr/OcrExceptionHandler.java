package com.capstone.itemsplit.ocr;
import com.capstone.itemsplit.common.response.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
@RestControllerAdvice @org.springframework.core.annotation.Order(-1)
public class OcrExceptionHandler {
    @ExceptionHandler(OcrRequestException.class)
    ResponseEntity<ApiResponse<Void>> handle(OcrRequestException exception) {
        return ResponseEntity.status(exception.getErrorCode().getStatus()).body(ApiResponse.failure(
            ErrorResponse.of(exception.getErrorCode(),exception.getMessage(),
                List.of(new ErrorResponse.FieldErrorDetail(null,null,exception.reason())))));
    }
}
