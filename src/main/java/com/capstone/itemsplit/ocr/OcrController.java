package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.common.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/receipts/{receiptId}/ocr/jobs")
@ConditionalOnProperty(name="app.ocr.enabled",havingValue="true")
public class OcrController {
    private final OcrService service;
    @PostMapping
    ResponseEntity<ApiResponse<OcrViews.JobView>> start(@PathVariable Long roomId,@PathVariable Long receiptId,
        @AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody StartRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(service.start(roomId,receiptId,userId(user),request.requestId())));
    }
    @GetMapping("/latest")
    ApiResponse<OcrViews.JobView> latest(@PathVariable Long roomId,@PathVariable Long receiptId,@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.latest(roomId,receiptId,userId(user)));
    }
    @GetMapping("/{jobId}")
    ApiResponse<OcrViews.JobView> get(@PathVariable Long roomId,@PathVariable Long receiptId,@PathVariable Long jobId,@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.get(roomId,receiptId,jobId,userId(user)));
    }
    @PostMapping("/{jobId}/images/{imageId}/retry")
    ResponseEntity<ApiResponse<OcrViews.JobView>> retry(@PathVariable Long roomId,@PathVariable Long receiptId,@PathVariable Long jobId,
        @PathVariable Long imageId,@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.accepted().body(ApiResponse.success(service.retry(roomId,receiptId,jobId,imageId,userId(user))));
    }
    static Long userId(AuthenticatedUser user) {if(user==null)throw new ApiException(ErrorCode.UNAUTHORIZED);return user.id();}
    public record StartRequest(@NotBlank @Size(max=64) String requestId){}
}
