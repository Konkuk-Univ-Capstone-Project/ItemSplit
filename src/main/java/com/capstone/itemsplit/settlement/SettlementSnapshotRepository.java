package com.capstone.itemsplit.settlement;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface SettlementSnapshotRepository extends JpaRepository<SettlementSnapshot,Long> {
    Optional<SettlementSnapshot> findByRoomIdAndRequestId(Long roomId,String requestId);
    Optional<SettlementSnapshot> findByIdAndRoomId(Long id,Long roomId);
    @Query("select coalesce(max(s.version),0) from SettlementSnapshot s where s.room.id=:roomId")
    long latestVersion(@Param("roomId") Long roomId);
}
