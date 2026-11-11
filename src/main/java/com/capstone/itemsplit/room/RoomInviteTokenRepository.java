package com.capstone.itemsplit.room;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomInviteTokenRepository extends JpaRepository<RoomInviteToken, Long> {

    @org.springframework.data.jpa.repository.Query("select t.room.id from RoomInviteToken t where t.token = :token and t.expiresAt > :now")
    Optional<Long> findValidRoomId(String token, java.time.LocalDateTime now);

	boolean existsByToken(String token);

	Optional<RoomInviteToken> findByRoomId(Long roomId);

	Optional<RoomInviteToken> findByToken(String token);

}
