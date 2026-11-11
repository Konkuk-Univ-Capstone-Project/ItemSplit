package com.capstone.itemsplit.settlement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementSnapshotShareTokenRepository extends JpaRepository<SettlementSnapshotShareToken,Long> {
    @org.springframework.data.jpa.repository.Query("select t.snapshot.room.id from SettlementSnapshotShareToken t where t.tokenHash=:hash")
    Optional<Long> findRoomIdByHash(@org.springframework.data.repository.query.Param("hash") String hash);
    Optional<SettlementSnapshotShareToken> findByTokenHash(String tokenHash);
    Optional<SettlementSnapshotShareToken> findBySnapshotId(Long snapshotId);
}
