package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserService;
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
	private final AssignmentRepository assignmentRepository;
	private final ItemRepository itemRepository;
	private final ReceiptRepository receiptRepository;
	private final RoomInviteTokenRepository roomInviteTokenRepository;
	private final RoomShareTokenRepository roomShareTokenRepository;
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
	public void deleteRoom(Long roomId, Long userId) {
		Room room = roomRepository.findById(roomId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Room was not found."));
		if (!room.getOwner().getId().equals(userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Only room owner can delete this room.");
		}

		List<Long> receiptIds = receiptRepository.findAllByRoomId(roomId).stream()
			.map(Receipt::getId)
			.toList();
		if (!receiptIds.isEmpty()) {
			List<Long> itemIds = itemRepository.findAllByReceiptIdIn(receiptIds).stream()
				.map(Item::getId)
				.toList();
			if (!itemIds.isEmpty()) {
				assignmentRepository.deleteAllByItemIdIn(itemIds);
			}
			receiptIds.forEach(itemRepository::deleteAllByReceiptId);
			receiptRepository.deleteAllById(receiptIds);
		}

		roomInviteTokenRepository.findByRoomId(roomId).ifPresent(roomInviteTokenRepository::delete);
		roomShareTokenRepository.findByRoomId(roomId).ifPresent(roomShareTokenRepository::delete);
		roomMemberRepository.deleteAll(roomMemberRepository.findAllByRoomId(roomId));
		roomRepository.delete(room);
	}

	@Transactional
	public RoomMemberResponse addManualMember(Long roomId, Long userId, String displayName) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		RoomMember roomMember = roomMemberRepository.save(RoomMember.createManual(room, displayName.trim()));
		return RoomMemberResponse.from(roomMember, room.getOwner().getId());
	}

	@Transactional
	public RoomMemberResponse updateMemberName(Long roomId, Long memberId, Long userId, String displayName) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		RoomMember roomMember = roomMemberRepository.findByRoomIdAndIdWithUser(roomId, memberId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Member was not found in this room."));

		roomMember.rename(displayName.trim());
		return RoomMemberResponse.from(roomMember, room.getOwner().getId());
	}

	@Transactional
	public void deleteMember(Long roomId, Long memberId, Long userId) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		RoomMember roomMember = roomMemberRepository.findByRoomIdAndIdWithUser(roomId, memberId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Member was not found in this room."));

		User memberUser = roomMember.getUser();
		if (memberUser != null && memberUser.getId().equals(room.getOwner().getId())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Room owner cannot be deleted.");
		}

		List<Receipt> payerReceipts = receiptRepository.findAllByRoomIdAndPayerId(roomId, memberId);
		if (!payerReceipts.isEmpty()) {
			throw new ApiException(
				ErrorCode.VALIDATION_ERROR,
				buildPayerDeletionMessage(roomMember, payerReceipts)
			);
		}

		assignmentRepository.deleteAllByRoomMemberId(memberId);
		roomMemberRepository.delete(roomMember);
	}

	private String buildPayerDeletionMessage(RoomMember roomMember, List<Receipt> receipts) {
		String receiptLabel = String.join(
			", ",
			receipts.stream()
				.map(receipt -> "'" + receipt.getName() + "'")
				.toList()
		);
		return "영수증 " + receiptLabel + "에서 '" + roomMember.getDisplayName()
			+ "'가 결제자로 지정되어 있습니다. 결제자를 해제한 뒤 삭제해주십시오.";
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

	public JoinOptionsResult getJoinOptions(String token) {
		Room room = resolveRoomByInviteToken(token);
		List<RoomMemberResponse> members = roomMemberRepository.findAllByRoomId(room.getId())
			.stream()
			.map(roomMember -> RoomMemberResponse.from(roomMember, room.getOwner().getId()))
			.toList();

		return JoinOptionsResult.from(room, members);
	}

	@Transactional
	public JoinRoomResult joinRoom(String token, Long userId, Long memberId, String displayName) {
		Room room = resolveRoomByInviteToken(token);
		User user = userService.getById(userId);

		boolean alreadyMember = roomMemberRepository.existsByRoomIdAndUserId(room.getId(), userId);
		if (alreadyMember) {
			RoomMember roomMember = roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
				.orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Room member state is inconsistent."));
			return JoinRoomResult.from(room, roomMember, false);
		}

		RoomMember joinedMember;
		if (memberId != null) {
			RoomMember targetMember = roomMemberRepository.findByRoomIdAndIdWithUser(room.getId(), memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Member was not found in this room."));
			if (targetMember.isLinkedUser()) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR, "This member is already linked to another user.");
			}
			targetMember.claim(user, resolveDisplayName(displayName, targetMember.getDisplayName(), user));
			joinedMember = targetMember;
		} else {
			joinedMember = roomMemberRepository.save(RoomMember.create(room, user, resolveDisplayName(displayName, null, user)));
		}

		return JoinRoomResult.from(room, joinedMember, true);
	}

	private Room resolveRoomByInviteToken(String token) {
		RoomInviteToken roomInviteToken = roomInviteTokenRepository.findByToken(token)
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Invite token is invalid or expired."));

		if (roomInviteToken.isExpired(LocalDateTime.now())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invite token is invalid or expired.");
		}

		return roomInviteToken.getRoom();
	}

	private String resolveDisplayName(String requestedName, String fallbackName, User user) {
		if (requestedName != null && !requestedName.isBlank()) {
			return requestedName.trim();
		}
		if (fallbackName != null && !fallbackName.isBlank()) {
			return fallbackName.trim();
		}
		return user.getNickname();
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

	public record RoomMemberResponse(
		Long memberId,
		Long userId,
		String email,
		String nickname,
		boolean linked,
		boolean owner
	) {

		private static RoomMemberResponse from(RoomMember roomMember, Long ownerId) {
			User user = roomMember.getUser();
			return new RoomMemberResponse(
				roomMember.getId(),
				user != null ? user.getId() : null,
				user != null ? user.getEmail() : null,
				roomMember.getDisplayName(),
				user != null,
				user != null && user.getId().equals(ownerId)
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

	public record JoinOptionsResult(Long roomId, String roomName, List<RoomMemberResponse> members) {

		private static JoinOptionsResult from(Room room, List<RoomMemberResponse> members) {
			return new JoinOptionsResult(room.getId(), room.getName(), members);
		}

	}

	public record JoinRoomResult(
		Long roomId,
		String roomName,
		Long memberId,
		String memberNickname,
		boolean joined
	) {

		private static JoinRoomResult from(Room room, RoomMember roomMember, boolean joined) {
			return new JoinRoomResult(
				room.getId(),
				room.getName(),
				roomMember.getId(),
				roomMember.getDisplayName(),
				joined
			);
		}

	}

}
