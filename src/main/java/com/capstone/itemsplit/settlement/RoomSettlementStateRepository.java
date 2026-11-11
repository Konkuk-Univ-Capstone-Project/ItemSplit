package com.capstone.itemsplit.settlement;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoomSettlementStateRepository extends JpaRepository<RoomSettlementState,Long> {
    @org.springframework.data.jpa.repository.Query("select s.status from RoomSettlementState s where s.roomId = :roomId")
    java.util.Optional<String> findStatusByRoomId(Long roomId);
}
