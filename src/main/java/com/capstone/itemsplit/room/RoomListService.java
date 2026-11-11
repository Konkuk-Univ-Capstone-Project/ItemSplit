package com.capstone.itemsplit.room;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists the rooms the signed-in account belongs to, so they can be found from any device. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomListService {

	private final RoomMemberRepository roomMemberRepository;

	public RoomListResult getMyRooms(Long userId) {
		List<RoomListItem> rooms = roomMemberRepository.findAllByUserIdWithRoom(userId)
			.stream()
			.map(RoomListItem::from)
			.toList();
		return new RoomListResult(rooms);
	}

	public record RoomListResult(List<RoomListItem> rooms) {
	}

	public record RoomListItem(Long roomId, String roomName, Long myMemberId, boolean owner, LocalDateTime createdAt) {

		private static RoomListItem from(RoomMember roomMember) {
			Room room = roomMember.getRoom();
			return new RoomListItem(
				room.getId(),
				room.getName(),
				roomMember.getId(),
				room.getOwner().getId().equals(roomMember.getUser().getId()),
				room.getCreatedAt()
			);
		}

	}

}
