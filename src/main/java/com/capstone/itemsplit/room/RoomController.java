package com.capstone.itemsplit.room;

import com.capstone.itemsplit.auth.AuthenticatedUser;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

	private final RoomService roomService;

	@PostMapping
	public ResponseEntity<ApiResponse<CreateRoomResponse>> createRoom(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody CreateRoomRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.CreateRoomResult result = roomService.createRoom(userId, request.name());

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new CreateRoomResponse(
				result.roomId(),
				result.roomName(),
				result.ownerId(),
				result.ownerNickname()
			)));
	}

	@GetMapping("/{roomId}/members")
	public ApiResponse<RoomMembersResponse> getMembers(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.RoomMembersResult result = roomService.getMembers(roomId, userId);

		return ApiResponse.success(new RoomMembersResponse(
			result.roomId(),
			result.roomName(),
			result.members().stream()
				.map(member -> new RoomMemberResponse(
					member.userId(),
					member.email(),
					member.nickname(),
					member.owner()
				))
				.toList()
		));
	}

	@PostMapping("/{roomId}/invite-token")
	public ApiResponse<InviteTokenResponse> issueInviteToken(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.InviteTokenResult result = roomService.issueInviteToken(roomId, userId);

		return ApiResponse.success(new InviteTokenResponse(
			result.roomId(),
			result.roomName(),
			result.token(),
			result.expiresAt()
		));
	}

	@PostMapping("/join")
	public ApiResponse<JoinRoomResponse> joinRoom(
		@RequestParam String token,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.JoinRoomResult result = roomService.joinRoom(token, userId);

		return ApiResponse.success(new JoinRoomResponse(
			result.roomId(),
			result.roomName(),
			result.joined()
		));
	}

	private Long requireAuthenticatedUser(AuthenticatedUser authenticatedUser) {
		if (authenticatedUser == null) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
		}

		return authenticatedUser.id();
	}

	public record CreateRoomRequest(
		@NotBlank(message = "name must not be blank")
		@Size(max = 50, message = "name must be 50 characters or fewer")
		String name
	) {
	}

	public record CreateRoomResponse(Long roomId, String roomName, Long ownerId, String ownerNickname) {
	}

	public record RoomMembersResponse(Long roomId, String roomName, java.util.List<RoomMemberResponse> members) {
	}

	public record RoomMemberResponse(Long userId, String email, String nickname, boolean owner) {
	}

	public record InviteTokenResponse(
		Long roomId,
		String roomName,
		String token,
		java.time.LocalDateTime expiresAt
	) {
	}

	public record JoinRoomResponse(Long roomId, String roomName, boolean joined) {
	}

}
