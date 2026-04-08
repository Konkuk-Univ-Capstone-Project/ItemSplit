package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.room.RoomRepository;
import com.capstone.itemsplit.domain.roominvitetoken.RoomInviteToken;
import com.capstone.itemsplit.domain.roominvitetoken.RoomInviteTokenRepository;
import com.capstone.itemsplit.domain.roommember.RoomMember;
import com.capstone.itemsplit.domain.roommember.RoomMemberRepository;
import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {

	private static final long INVITE_TOKEN_EXPIRATION_DAYS = 7L;

	private final RoomRepository roomRepository;
	private final RoomMemberRepository roomMemberRepository;
	private final RoomInviteTokenRepository roomInviteTokenRepository;
	private final UserService userService;
	private final InviteTokenGenerator inviteTokenGenerator;
	private final RoomAuthorizationService roomAuthorizationService;

	@Transactional
	public CreateRoomResult createRoom(Long ownerId, String roomName) {
		User owner = userService.getById(ownerId);
		Room room = roomRepository.save(Room.create(roomName.trim(), owner));
		roomMemberRepository.save(RoomMember.create(room, owner));

		return CreateRoomResult.from(room, owner);
	}

	public RoomMembersResult getMembers(Long roomId, Long userId) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		List<RoomMemberResponse> members = roomMemberRepository.findAllByRoomId(roomId)
			.stream()
			.map(roomMember -> RoomMemberResponse.from(roomMember, room.getOwner().getId()))
			.toList();

		return RoomMembersResult.from(room, members);
	}

	@Transactional
	public InviteTokenResult issueInviteToken(Long roomId, Long userId) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		String token = generateUniqueToken();
		LocalDateTime expiresAt = LocalDateTime.now().plusDays(INVITE_TOKEN_EXPIRATION_DAYS);

		RoomInviteToken roomInviteToken = roomInviteTokenRepository.findByRoomId(roomId)
			.map(existingToken -> {
				existingToken.reissue(token, expiresAt);
				return existingToken;
			})
			.orElseGet(() -> RoomInviteToken.create(room, token, expiresAt));

		RoomInviteToken savedToken = roomInviteTokenRepository.save(roomInviteToken);
		return InviteTokenResult.from(room, savedToken);
	}

	@Transactional
	public JoinRoomResult joinRoom(String token, Long userId) {
		RoomInviteToken roomInviteToken = roomInviteTokenRepository.findByToken(token)
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Invite token is invalid or expired."));

		if (roomInviteToken.isExpired(LocalDateTime.now())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invite token is invalid or expired.");
		}

		Room room = roomInviteToken.getRoom();
		User user = userService.getById(userId);

		boolean alreadyMember = roomMemberRepository.existsByRoomIdAndUserId(room.getId(), userId);
		if (!alreadyMember) {
			roomMemberRepository.save(RoomMember.create(room, user));
		}

		return new JoinRoomResult(room.getId(), room.getName(), !alreadyMember);
	}

	private String generateUniqueToken() {
		String token = inviteTokenGenerator.generate();
		while (roomInviteTokenRepository.existsByToken(token)) {
			token = inviteTokenGenerator.generate();
		}
		return token;
	}

	public record CreateRoomResult(Long roomId, String roomName, Long ownerId, String ownerNickname) {

		private static CreateRoomResult from(Room room, User owner) {
			return new CreateRoomResult(room.getId(), room.getName(), owner.getId(), owner.getNickname());
		}

	}

	public record RoomMembersResult(Long roomId, String roomName, List<RoomMemberResponse> members) {

		private static RoomMembersResult from(Room room, List<RoomMemberResponse> members) {
			return new RoomMembersResult(room.getId(), room.getName(), members);
		}

	}

	public record RoomMemberResponse(Long userId, String email, String nickname, boolean owner) {

		private static RoomMemberResponse from(RoomMember roomMember, Long ownerId) {
			User user = roomMember.getUser();
			return new RoomMemberResponse(
				user.getId(),
				user.getEmail(),
				user.getNickname(),
				user.getId().equals(ownerId)
			);
		}

	}

	public record InviteTokenResult(Long roomId, String roomName, String token, LocalDateTime expiresAt) {

		private static InviteTokenResult from(Room room, RoomInviteToken roomInviteToken) {
			return new InviteTokenResult(
				room.getId(),
				room.getName(),
				roomInviteToken.getToken(),
				roomInviteToken.getExpiresAt()
			);
		}

	}

	public record JoinRoomResult(Long roomId, String roomName, boolean joined) {
	}

}
