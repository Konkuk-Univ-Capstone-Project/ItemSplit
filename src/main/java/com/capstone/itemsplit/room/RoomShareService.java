package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomShareService {

	private static final long SHARE_TOKEN_EXPIRATION_DAYS = 14L;

	private final RoomShareTokenRepository roomShareTokenRepository;
	private final InviteTokenGenerator inviteTokenGenerator;
	private final RoomAuthorizationService roomAuthorizationService;

	@Transactional
	public ShareTokenResult issueShareToken(Long roomId, Long userId) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		String token = generateUniqueToken();
		LocalDateTime expiresAt = LocalDateTime.now().plusDays(SHARE_TOKEN_EXPIRATION_DAYS);

		RoomShareToken roomShareToken = roomShareTokenRepository.findByRoomId(roomId)
			.map(existingToken -> {
				existingToken.reissue(token, expiresAt);
				return existingToken;
			})
			.orElseGet(() -> RoomShareToken.create(room, token, expiresAt));

		RoomShareToken savedToken = roomShareTokenRepository.save(roomShareToken);
		return ShareTokenResult.from(room, savedToken);
	}

	public SharedRoomResult resolveSharedRoom(String token) {
		RoomShareToken roomShareToken = roomShareTokenRepository.findByToken(token)
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Share token is invalid or expired."));

		if (roomShareToken.isExpired(LocalDateTime.now())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Share token is invalid or expired.");
		}

		return SharedRoomResult.from(roomShareToken.getRoom(), roomShareToken);
	}

	private String generateUniqueToken() {
		String token = inviteTokenGenerator.generate();
		while (roomShareTokenRepository.existsByToken(token)) {
			token = inviteTokenGenerator.generate();
		}
		return token;
	}

	public record ShareTokenResult(
		Long roomId,
		String roomName,
		String token,
		LocalDateTime expiresAt,
		boolean readOnly
	) {

		private static ShareTokenResult from(Room room, RoomShareToken roomShareToken) {
			return new ShareTokenResult(
				room.getId(),
				room.getName(),
				roomShareToken.getToken(),
				roomShareToken.getExpiresAt(),
				true
			);
		}

	}

	public record SharedRoomResult(Room room, LocalDateTime expiresAt, boolean readOnly) {

		private static SharedRoomResult from(Room room, RoomShareToken roomShareToken) {
			return new SharedRoomResult(room, roomShareToken.getExpiresAt(), true);
		}

	}

}
