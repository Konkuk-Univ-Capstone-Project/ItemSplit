package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rooms/{roomId}/receipts")
@RequiredArgsConstructor
public class ReceiptController {
    private final ReceiptService receiptService;

    @GetMapping
    public ApiResponse<List<ReceiptService.ReceiptSummaryResult>> getReceipts(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(receiptService.getReceipts(roomId, userId(user)));
    }

    @GetMapping("/{receiptId}")
    public ApiResponse<ReceiptService.ReceiptDetailResult> getReceipt(@PathVariable Long roomId, @PathVariable Long receiptId,
        @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(receiptService.getReceipt(roomId, receiptId, userId(user)));
    }

    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ReceiptService.UploadReceiptImageResult>> uploadReceiptImage(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser user, @RequestPart("file") MultipartFile file,
        @RequestParam(required = false) String name) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            receiptService.uploadReceiptImage(roomId, userId(user), file, name)));
    }

    @PostMapping("/manual")
    public ResponseEntity<ApiResponse<ReceiptService.ReceiptDetailResult>> createManualReceipt(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateManualReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(receiptService.createManualReceipt(
            roomId, userId(user), request.requestId(), request.name(), request.payerMemberId(), request.declaredTotal(),
            request.purchasedAt(), request.items().stream().map(SaveItemRequest::command).toList())));
    }

    @PutMapping("/{receiptId}")
    public ApiResponse<ReceiptService.ReceiptDetailResult> updateReceipt(@PathVariable Long roomId, @PathVariable Long receiptId,
        @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody UpdateReceiptRequest request) {
        return ApiResponse.success(receiptService.updateReceipt(roomId, receiptId, userId(user), request.name(),
            request.payerMemberId(), request.declaredTotal(), request.purchasedAt()));
    }

    @PutMapping("/{receiptId}/contents")
    public ApiResponse<ReceiptService.ReceiptDetailResult> saveContents(@PathVariable Long roomId, @PathVariable Long receiptId,
        @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody SaveContentsRequest request) {
        return ApiResponse.success(receiptService.saveContents(roomId, receiptId, userId(user), request.name(),
            request.payerMemberId(), request.declaredTotal(), request.purchasedAt(),
            request.items().stream().map(SaveItemRequest::command).toList()));
    }

    @DeleteMapping("/{receiptId}")
    public ResponseEntity<Void> deleteReceipt(@PathVariable Long roomId, @PathVariable Long receiptId,
        @AuthenticationPrincipal AuthenticatedUser user) {
        receiptService.deleteReceipt(roomId, receiptId, userId(user));
        return ResponseEntity.noContent().build();
    }

    private Long userId(AuthenticatedUser user) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return user.id();
    }

    public record CreateManualReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 100) String name,
        Long payerMemberId,
        @Positive @Max(10_000_000) Long declaredTotal,
        LocalDate purchasedAt,
        @NotEmpty List<@NotNull @Valid SaveItemRequest> items
    ) { }

    public record SaveContentsRequest(
        @NotBlank @Size(max = 100) String name,
        Long payerMemberId,
        @Positive @Max(10_000_000) Long declaredTotal,
        LocalDate purchasedAt,
        @NotEmpty List<@NotNull @Valid SaveItemRequest> items
    ) { }

    public record SaveItemRequest(
        Long itemId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive @Max(1_000_000) Long price,
        @NotNull @Positive @Max(999) Integer quantity,
        @NotNull List<@NotNull @Positive Long> memberIds,
        @NotNull Boolean excludedFromSettlement
    ) {
        ReceiptService.SaveItemCommand command() {
            return new ReceiptService.SaveItemCommand(itemId, name, price, quantity, memberIds, excludedFromSettlement);
        }
    }

    public record UpdateReceiptRequest(
        @NotBlank @Size(max = 100) String name,
        Long payerMemberId,
        @Positive @Max(10_000_000) Long declaredTotal,
        LocalDate purchasedAt
    ) { }
}
