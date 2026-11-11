package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.room.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="app.settlement.snapshots.enabled",havingValue="true")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class SettlementSharingService {
    private final RoomAuthorizationService authorization;
    private final RoomRepository rooms;
    private final RoomSettlementStateRepository states;
    private final SettlementSnapshotRepository snapshots;
    private final SettlementSnapshotShareTokenRepository shares;
    private final InviteTokenGenerator generator;
    private final EntityManager entityManager;

    public IssuedShare issue(Long roomId,Long snapshotId,Long userId) {
        Room room=authorization.checkMemberForUpdate(roomId,userId);
        if(!room.getOwner().getId().equals(userId))
            throw new SettlementRequestException(ErrorCode.FORBIDDEN,"FORBIDDEN","방장만 확정본 공유 링크를 발급할 수 있습니다.");
        var snapshot=snapshots.findByIdAndRoomId(snapshotId,roomId).orElseThrow(SettlementSharingService::notFound);
        var state=states.findById(roomId).orElse(null);
        entityManager.flush();entityManager.refresh(snapshot);
        if(state!=null) entityManager.refresh(state);
        if(state==null || !"CONFIRMED".equals(state.getStatus()) || !snapshotId.equals(state.getCurrentSnapshotId())
            || !"CONFIRMED".equals(snapshot.getStatus()))
            throw new SettlementRequestException(ErrorCode.CONFLICT,"SETTLEMENT_VERSION_CONFLICT","현재 확정된 정산만 공유 링크를 발급할 수 있습니다.");
        var row=shares.findBySnapshotId(snapshotId).orElse(null);
        if(row==null) row=SettlementSnapshotShareToken.create(snapshot);
        else entityManager.refresh(row);
        String token=generator.generate();
        row.rotate(SettlementConfirmationInput.hash(token),LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        shares.save(row);
        return new IssuedShare(token,row.getExpiresAt(),"/share/settlements/"+token);
    }

    public PublicSnapshot read(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw notFound();
        String hash=SettlementConfirmationInput.hash(token);
        Long roomId=shares.findRoomIdByHash(hash).orElseThrow(SettlementSharingService::notFound);
        // Issuance, completion, reopen and deletion use this same lock. The initial lookup may
        // have waited behind a rotation; refresh and revalidate after acquiring the room lock.
        rooms.findByIdForUpdate(roomId).orElseThrow(SettlementSharingService::notFound);
        var share=shares.findByTokenHash(hash).orElseThrow(SettlementSharingService::notFound);
        entityManager.refresh(share);
        if(!share.valid(hash,LocalDateTime.now())) throw notFound();
        var snapshot=share.getSnapshot();entityManager.refresh(snapshot);
        return new PublicSnapshot(snapshot.getId(),snapshot.getRoomName(),snapshot.getVersion(),snapshot.getStatus(),
            snapshot.getConfirmedAt(),snapshot.getReopenedAt(),share.getExpiresAt(),
            snapshot.getMembers().stream().map(m->new PublicMember(m.getMemberId(),m.getDisplayName(),m.getPaid(),m.getBurden(),m.getNet())).toList(),
            snapshot.getTransfers().stream().map(t->{
                entityManager.refresh(t);
                return new PublicTransfer(t.getFromMemberId(),t.getToMemberId(),t.getAmount(),t.getStatus());
            }).toList());
    }
    private static ApiException notFound() {return new ApiException(ErrorCode.NOT_FOUND);}
    public record IssuedShare(String token,LocalDateTime expiresAt,String shareUrl) { }
    public record PublicMember(Long memberId,String displayName,long paid,long burden,long net) { }
    public record PublicTransfer(Long fromMemberId,Long toMemberId,long amount,String status) { }
    public record PublicSnapshot(Long snapshotId,String roomName,long version,String status,LocalDateTime confirmedAt,
        LocalDateTime reopenedAt,LocalDateTime expiresAt,List<PublicMember> members,List<PublicTransfer> transfers) { }
}
