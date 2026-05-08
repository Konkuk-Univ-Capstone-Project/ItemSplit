package com.capstone.itemsplit.room;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomInviteTokenRepository extends JpaRepository<RoomInviteToken, Long> {

	boolean existsByToken(String token);

	Optional<RoomInviteToken> findByRoomId(Long roomId);

	Optional<RoomInviteToken> findByToken(String token);

}
