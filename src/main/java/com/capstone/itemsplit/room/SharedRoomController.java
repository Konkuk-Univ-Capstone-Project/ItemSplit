package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.response.ApiResponse;
import com.capstone.itemsplit.settlement.SettlementService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shared/rooms")
@RequiredArgsConstructor
public class SharedRoomController {

	private final RoomShareService roomShareService;
	private final SettlementService settlementService;

	@GetMapping("/{token}/settlements")
	public ApiResponse<SharedSettlementResponse> getSharedSettlement(@PathVariable String token) {
		RoomShareService.SharedRoomResult sharedRoom = roomShareService.resolveSharedRoom(token);
		SettlementService.SettlementResult settlement = settlementService.calculateShared(sharedRoom.room());

		List<MemberSettlementResponse> members = settlement.members().stream()
			.map(member -> new MemberSettlementResponse(
				member.userId(),
				member.nickname(),
				member.burden(),
				member.paid(),
				member.net()
			))
			.toList();

		return ApiResponse.success(new SharedSettlementResponse(
			settlement.roomId(),
			settlement.roomName(),
			sharedRoom.expiresAt(),
			sharedRoom.readOnly(),
			members
		));
	}

	public record SharedSettlementResponse(
		Long roomId,
		String roomName,
		LocalDateTime shareExpiresAt,
		boolean readOnly,
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
