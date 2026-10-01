package com.capstone.itemsplit.room;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomAuthorizationService {

	private final RoomRepository roomRepository;
	private final RoomMemberRepository roomMemberRepository;

    @Transactional
    public Room checkMemberForUpdate(Long roomId, Long userId) {
        Room room = roomRepository.findByIdForUpdate(roomId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Room was not found."));
        if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You are not a member of this room.");
        }
        return room;
    }

	public Room checkMember(Long roomId, Long userId) {
		Room room = roomRepository.findById(roomId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Room was not found."));

		if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "You are not a member of this room.");
		}

		return room;
	}

}
