package com.capstone.itemsplit.domain.roommember;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

	boolean existsByRoomIdAndUserId(Long roomId, Long userId);

	Optional<RoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

	@Query("""
		select roomMember
		from RoomMember roomMember
		join fetch roomMember.user user
		where roomMember.room.id = :roomId
		order by roomMember.id asc
		""")
	List<RoomMember> findAllByRoomId(Long roomId);

}
