package com.capstone.itemsplit.room;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomShareTokenRepository extends JpaRepository<RoomShareToken, Long> {

	boolean existsByToken(String token);

	Optional<RoomShareToken> findByRoomId(Long roomId);

	@Query("""
		select shareToken
		from RoomShareToken shareToken
		join fetch shareToken.room room
		where shareToken.token = :token
		""")
	Optional<RoomShareToken> findByToken(@Param("token") String token);

}
