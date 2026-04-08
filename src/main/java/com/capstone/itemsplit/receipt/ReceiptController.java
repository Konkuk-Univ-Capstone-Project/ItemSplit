package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.domain.receipt.ReceiptSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rooms/{roomId}/receipts")
@RequiredArgsConstructor
public class ReceiptController {

	private final ReceiptService receiptService;

	@PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<UploadReceiptImageResponse>> uploadReceiptImage(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestPart("file") MultipartFile file,
		@RequestParam(required = false) String name
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		ReceiptService.UploadReceiptImageResult result = receiptService.uploadReceiptImage(roomId, userId, file, name);

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new UploadReceiptImageResponse(
				result.receiptId(),
				result.roomId(),
				result.name(),
				result.sourceType(),
				result.storedPath(),
				result.originalFilename(),
				result.contentType(),
				result.fileSize()
			)));
	}

	@PostMapping("/manual")
	public ResponseEntity<ApiResponse<CreateManualReceiptResponse>> createManualReceipt(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody CreateManualReceiptRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		ReceiptService.CreateManualReceiptResult result = receiptService.createManualReceipt(
			roomId,
			userId,
			request.name(),
			request.items().stream()
				.map(item -> new ReceiptService.ManualReceiptItemCommand(
					item.name(),
					item.price(),
					item.quantity()
				))
				.toList()
		);

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new CreateManualReceiptResponse(
				result.receiptId(),
				result.roomId(),
				result.name(),
				result.sourceType(),
				result.items().stream()
					.map(item -> new ManualReceiptItemResponse(
						item.itemId(),
						item.name(),
						item.price(),
						item.quantity()
					))
					.toList()
			)));
	}

	private Long requireAuthenticatedUser(AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}

		return authenticatedUser.id();
	}

	public record UploadReceiptImageResponse(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		String storedPath,
		String originalFilename,
		String contentType,
		Long fileSize
	) {
	}

	public record CreateManualReceiptRequest(
		@NotBlank(message = "name must not be blank")
		@Size(max = 100, message = "name must be 100 characters or fewer")
		String name,
		@NotEmpty(message = "items must not be empty")
		List<@Valid ManualReceiptItemRequest> items
	) {
	}

	public record ManualReceiptItemRequest(
		@NotBlank(message = "name must not be blank")
		@Size(max = 100, message = "name must be 100 characters or fewer")
		String name,
		@NotNull(message = "price must not be null")
		@Positive(message = "price must be greater than 0")
		Integer price,
		@NotNull(message = "quantity must not be null")
		@Positive(message = "quantity must be greater than 0")
		Integer quantity
	) {
	}

	public record CreateManualReceiptResponse(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		List<ManualReceiptItemResponse> items
	) {
	}

	public record ManualReceiptItemResponse(
		Long itemId,
		String name,
		int price,
		int quantity
	) {
	}

}
