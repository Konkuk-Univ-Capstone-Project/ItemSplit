package com.capstone.itemsplit.room;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

	boolean existsByRoomIdAndUserId(Long roomId, Long userId);

	Optional<RoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

	@Query("""
		select roomMember
		from RoomMember roomMember
		left join fetch roomMember.user user
		where roomMember.room.id = :roomId
		order by roomMember.id asc
		""")
	List<RoomMember> findAllByRoomId(Long roomId);

	@Query("""
		select roomMember
		from RoomMember roomMember
		left join fetch roomMember.user user
		where roomMember.room.id = :roomId
		  and roomMember.user.id in :userIds
		order by roomMember.id asc
		""")
	List<RoomMember> findAllByRoomIdAndUserIdIn(@Param("roomId") Long roomId, @Param("userIds") Collection<Long> userIds);

	@Query("""
		select roomMember
		from RoomMember roomMember
		left join fetch roomMember.user user
		where roomMember.room.id = :roomId
		  and roomMember.id in :memberIds
		order by roomMember.id asc
		""")
	List<RoomMember> findAllByRoomIdAndIdIn(@Param("roomId") Long roomId, @Param("memberIds") Collection<Long> memberIds);

	@Query("""
		select roomMember
		from RoomMember roomMember
		left join fetch roomMember.user user
		where roomMember.room.id = :roomId
		  and roomMember.id = :memberId
		""")
	Optional<RoomMember> findByRoomIdAndIdWithUser(@Param("roomId") Long roomId, @Param("memberId") Long memberId);

	@Query("""
		select roomMember
		from RoomMember roomMember
		left join fetch roomMember.user user
		where roomMember.room.id = :roomId
		  and roomMember.user is null
		order by roomMember.id asc
		""")
	List<RoomMember> findAllUnlinkedByRoomId(@Param("roomId") Long roomId);

}
