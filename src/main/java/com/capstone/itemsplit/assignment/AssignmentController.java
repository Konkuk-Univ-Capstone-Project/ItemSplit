package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/rooms/{roomId}/receipts/{receiptId}/items/{itemId}/assignees")
@RequiredArgsConstructor
public class AssignmentController {

	private final AssignmentService assignmentService;

	@GetMapping
	public ApiResponse<AssigneesResponse> getAssignees(
		@PathVariable Long roomId,
		@PathVariable Long receiptId,
		@PathVariable Long itemId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long requesterId = requireAuthenticatedUser(authenticatedUser);
		AssignmentService.AssigneesResult result = assignmentService.getAssignees(
			roomId,
			receiptId,
			itemId,
			requesterId
		);

		return ApiResponse.success(toResponse(result));
	}

	@PutMapping
	public ApiResponse<AssigneesResponse> replaceAssignees(
		@PathVariable Long roomId,
		@PathVariable Long receiptId,
		@PathVariable Long itemId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody ReplaceAssigneesRequest request
	) {
		Long requesterId = requireAuthenticatedUser(authenticatedUser);
		AssignmentService.AssigneesResult result = assignmentService.replaceAssignees(
			roomId,
			receiptId,
			itemId,
			requesterId,
			request.memberIds()
		);

		return ApiResponse.success(toResponse(result));
	}

	private AssigneesResponse toResponse(AssignmentService.AssigneesResult result) {
		return new AssigneesResponse(
			result.roomId(),
			result.receiptId(),
			result.itemId(),
			result.itemName(),
			result.assignees().stream()
				.map(assignee -> new AssigneeResponse(
					assignee.memberId(),
					assignee.userId(),
					assignee.email(),
					assignee.nickname(),
					assignee.linked()
				))
				.toList()
		);
	}

	private Long requireAuthenticatedUser(AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}

		return authenticatedUser.id();
	}

	public record ReplaceAssigneesRequest(
		@NotNull(message = "memberIds must not be null")
		List<@NotNull(message = "memberIds must not contain null values") Long> memberIds
	) {
	}

	public record AssigneesResponse(
		Long roomId,
		Long receiptId,
		Long itemId,
		String itemName,
		List<AssigneeResponse> assignees
	) {
	}

	public record AssigneeResponse(Long memberId, Long userId, String email, String nickname, boolean linked) {
	}

}
