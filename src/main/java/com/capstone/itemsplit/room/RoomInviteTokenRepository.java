package com.capstone.itemsplit.room;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomInviteTokenRepository extends JpaRepository<RoomInviteToken, Long> {

	boolean existsByToken(String token);

	// Reads only the room id so the token entity is first loaded after the room lock, with its latest state.
	@Query("select token.room.id from RoomInviteToken token where token.token = :token")
	Optional<Long> findRoomIdByToken(@Param("token") String token);

	Optional<RoomInviteToken> findByRoomId(Long roomId);

	Optional<RoomInviteToken> findByToken(String token);

}
