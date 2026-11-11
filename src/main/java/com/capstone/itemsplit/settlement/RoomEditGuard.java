package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.room.Room;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Caller must hold the room write lock and have checked operation-specific permission. */
@Service
@RequiredArgsConstructor
public class RoomEditGuard {
    private final RoomSettlementStateRepository states;
    private final SettlementSnapshotRepository snapshots;

    // The guard performs no writes. Workers may catch the lock rejection to commit quota cleanup.
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = SettlementRequestException.class)
    public void requireEditable(Room lockedRoom) {
        // Scalar query reads current DB state rather than a pre-lock managed entity.
        String status = states.findStatusByRoomId(lockedRoom.getId()).orElse(null);
        if (status == null) {
            if (snapshots.latestVersion(lockedRoom.getId()) != 0)
                throw new IllegalStateException("Settlement history exists without room state");
            return;
        }
        if ("CONFIRMED".equals(status))
            throw new SettlementRequestException(ErrorCode.CONFLICT, "SETTLEMENT_LOCKED",
                "정산이 확정되어 수정할 수 없습니다. 재개방 후 다시 시도해 주세요.");
        if (!"OPEN".equals(status)) throw new IllegalStateException("Unknown settlement state");
    }
}
