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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

	private final RoomService roomService;
	private final RoomShareService roomShareService;

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
					member.memberId(),
					member.userId(),
					member.email(),
					member.nickname(),
					member.linked(),
					member.owner()
				))
				.toList()
		));
	}

	@DeleteMapping("/{roomId}")
	public ResponseEntity<Void> deleteRoom(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		roomService.deleteRoom(roomId, userId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{roomId}/members/manual")
	public ApiResponse<RoomMemberResponse> addManualMember(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody AddManualMemberRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.RoomMemberResponse member = roomService.addManualMember(roomId, userId, request.nickname());

		return ApiResponse.success(new RoomMemberResponse(
			member.memberId(),
			member.userId(),
			member.email(),
			member.nickname(),
			member.linked(),
			member.owner()
		));
	}

	@PutMapping("/{roomId}/members/{memberId}")
	public ApiResponse<RoomMemberResponse> updateMemberName(
		@PathVariable Long roomId,
		@PathVariable Long memberId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody UpdateRoomMemberRequest request
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.RoomMemberResponse member = roomService.updateMemberName(roomId, memberId, userId, request.nickname());

		return ApiResponse.success(new RoomMemberResponse(
			member.memberId(),
			member.userId(),
			member.email(),
			member.nickname(),
			member.linked(),
			member.owner()
		));
	}

	@DeleteMapping("/{roomId}/members/{memberId}")
	public ResponseEntity<Void> deleteMember(
		@PathVariable Long roomId,
		@PathVariable Long memberId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		roomService.deleteMember(roomId, memberId, userId);
		return ResponseEntity.noContent().build();
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

	@PostMapping("/{roomId}/share-token")
	public ApiResponse<ShareTokenResponse> issueShareToken(
		@PathVariable Long roomId,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomShareService.ShareTokenResult result = roomShareService.issueShareToken(roomId, userId);

		return ApiResponse.success(new ShareTokenResponse(
			result.roomId(),
			result.roomName(),
			result.token(),
			result.expiresAt(),
			result.readOnly()
		));
	}

	@PostMapping("/join")
	public ApiResponse<JoinRoomResponse> joinRoom(
		@RequestParam String token,
		@RequestParam(required = false) Long memberId,
		@RequestParam(required = false) String nickname,
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		Long userId = requireAuthenticatedUser(authenticatedUser);
		RoomService.JoinRoomResult result = roomService.joinRoom(token, userId, memberId, nickname);

		return ApiResponse.success(new JoinRoomResponse(
			result.roomId(),
			result.roomName(),
			result.memberId(),
			result.memberNickname(),
			result.joined()
		));
	}

	@GetMapping("/join-options")
	public ApiResponse<JoinOptionsResponse> getJoinOptions(@RequestParam String token) {
		RoomService.JoinOptionsResult result = roomService.getJoinOptions(token);

		return ApiResponse.success(new JoinOptionsResponse(
			result.roomId(),
			result.roomName(),
			result.members().stream()
				.map(member -> new RoomMemberResponse(
					member.memberId(),
					member.userId(),
					member.email(),
					member.nickname(),
					member.linked(),
					member.owner()
				))
				.toList()
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

	public record AddManualMemberRequest(
		@NotBlank(message = "nickname must not be blank")
		@Size(max = 50, message = "nickname must be 50 characters or fewer")
		String nickname
	) {
	}

	public record UpdateRoomMemberRequest(
		@NotBlank(message = "nickname must not be blank")
		@Size(max = 50, message = "nickname must be 50 characters or fewer")
		String nickname
	) {
	}

	public record CreateRoomResponse(Long roomId, String roomName, Long ownerId, String ownerNickname) {
	}

	public record RoomMembersResponse(Long roomId, String roomName, java.util.List<RoomMemberResponse> members) {
	}

	public record RoomMemberResponse(
		Long memberId,
		Long userId,
		String email,
		String nickname,
		boolean linked,
		boolean owner
	) {
	}

	public record InviteTokenResponse(
		Long roomId,
		String roomName,
		String token,
		java.time.LocalDateTime expiresAt
	) {
	}

	public record ShareTokenResponse(
		Long roomId,
		String roomName,
		String token,
		java.time.LocalDateTime expiresAt,
		boolean readOnly
	) {
	}

	public record JoinOptionsResponse(Long roomId, String roomName, java.util.List<RoomMemberResponse> members) {
	}

	public record JoinRoomResponse(
		Long roomId,
		String roomName,
		Long memberId,
		String memberNickname,
		boolean joined
	) {
	}

}
