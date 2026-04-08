package com.capstone.itemsplit.item;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/receipts/{receiptId}/items")
@RequiredArgsConstructor
public class ItemController {

	private final ItemService itemService;

	@PostMapping
	public ResponseEntity<ApiResponse<ItemResponse>> addItem(
		@PathVariable Long roomId,
		@PathVariable Long receiptId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody AddItemRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		ItemService.ItemResult result = itemService.addItem(
			roomId,
			receiptId,
			userId,
			request.name(),
			request.price(),
			request.quantity()
		);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(ItemResponse.from(result)));
	}

	@PutMapping("/{itemId}")
	public ApiResponse<ItemResponse> updateItem(
		@PathVariable Long roomId,
		@PathVariable Long receiptId,
		@PathVariable Long itemId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody UpdateItemRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		ItemService.ItemResult result = itemService.updateItem(
			roomId,
			receiptId,
			itemId,
			userId,
			request.name(),
			request.price(),
			request.quantity()
		);
		return ApiResponse.success(ItemResponse.from(result));
	}

	@DeleteMapping("/{itemId}")
	public ResponseEntity<Void> deleteItem(
		@PathVariable Long roomId,
		@PathVariable Long receiptId,
		@PathVariable Long itemId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		itemService.deleteItem(roomId, receiptId, itemId, userId);
		return ResponseEntity.noContent().build();
	}

	private Long requireAuthenticatedUser(AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}

		return authenticatedUser.id();
	}

	public record AddItemRequest(
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

	public record UpdateItemRequest(
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

	public record ItemResponse(
		Long itemId,
		Long receiptId,
		String name,
		int price,
		int quantity
	) {

		private static ItemResponse from(ItemService.ItemResult result) {
			return new ItemResponse(
				result.itemId(),
				result.receiptId(),
				result.name(),
				result.price(),
				result.quantity()
			);
		}

	}

}
