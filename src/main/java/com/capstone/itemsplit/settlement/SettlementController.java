package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/settlements")
@RequiredArgsConstructor
public class SettlementController {

	private final SettlementService settlementService;

	@GetMapping
	public ApiResponse<SettlementResponse> getSettlement(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		SettlementService.SettlementResult result = settlementService.calculate(roomId, userId);
		return ApiResponse.success(toResponse(result));
	}

	private SettlementResponse toResponse(SettlementService.SettlementResult result) {
		List<MemberSettlementResponse> members = result.members().stream()
			.map(m -> new MemberSettlementResponse(
				m.userId(),
				m.nickname(),
				m.burden(),
				m.paid(),
				m.net()
			))
			.toList();
		return new SettlementResponse(result.roomId(), result.roomName(), members);
	}

	private Long requireAuthenticatedUser(AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}
		return authenticatedUser.id();
	}

	public record SettlementResponse(
		Long roomId,
		String roomName,
		List<MemberSettlementResponse> members
	) {
	}

	public record MemberSettlementResponse(
		Long userId,
		String nickname,
		long burden,
		long paid,
		long net
	) {
	}

}
