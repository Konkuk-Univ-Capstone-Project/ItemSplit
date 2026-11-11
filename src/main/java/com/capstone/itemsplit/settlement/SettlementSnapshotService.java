package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.common.exception.*;
import com.capstone.itemsplit.room.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="app.settlement.snapshots.enabled",havingValue="true")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class SettlementSnapshotService {
    private final RoomAuthorizationService authorization;
    private final RoomSettlementStateRepository states;
    private final SettlementSnapshotRepository snapshots;
    private final SettlementService calculation;
    private final SettlementConfirmationInput inputs;
    private final jakarta.persistence.EntityManager entityManager;

    public StateView state(Long roomId,Long userId) {
        authorization.checkMemberForUpdate(roomId,userId);
        var state=loadState(roomId);
        return new StateView(roomId,state==null?"OPEN":state.getStatus(),state==null?null:state.getCurrentSnapshotId());
    }
    public PreviewView preview(Long roomId,Long userId) {
        Room room=authorization.checkMemberForUpdate(roomId,userId);
        return previewLocked(room,userId,loadState(roomId));
    }
    public Confirmation confirm(Long roomId,Long userId,ConfirmRequest request) {
        Room room=authorization.checkMemberForUpdate(roomId,userId);
        if(!room.getOwner().getId().equals(userId)) throw new SettlementRequestException(ErrorCode.FORBIDDEN,"FORBIDDEN","방장만 정산을 확정할 수 있습니다.");
        if(request==null || request.requestId()==null || request.requestId().isBlank() || request.requestId().length()>64
            || request.previewToken()==null || request.previewToken().isBlank() || request.previewToken().length()>256)
            throw new SettlementRequestException(ErrorCode.VALIDATION_ERROR,"SETTLEMENT_NOT_READY","requestId와 previewToken을 확인해 주세요.");
        String requestFingerprint=SettlementConfirmationInput.hash(request.previewToken());
        var prior=snapshots.findByRoomIdAndRequestId(roomId,request.requestId());
        if(prior.isPresent()) {
            if(!prior.get().getRequestFingerprint().equals(requestFingerprint))
                throw conflict("IDEMPOTENCY_KEY_REUSED","같은 requestId를 다른 요청에 사용할 수 없습니다.");
            return new Confirmation(view(prior.get()),false);
        }
        var state=loadState(roomId);
        if(state!=null && "CONFIRMED".equals(state.getStatus()))
            throw conflict("SETTLEMENT_VERSION_CONFLICT","이미 확정된 정산입니다.");
        PreviewView preview=previewLocked(room,userId,state);
        if(!preview.previewToken().equals(request.previewToken()))
            throw conflict("SETTLEMENT_INPUT_CHANGED","정산 입력이 변경되었습니다. 최신 미리보기를 확인해 주세요.");
        if(!preview.ready()) throw new SettlementRequestException(ErrorCode.VALIDATION_ERROR,"SETTLEMENT_NOT_READY","정산 입력과 OCR 반영 상태를 확인해 주세요.");
        var snapshot=snapshots.saveAndFlush(SettlementSnapshot.create(room,snapshots.latestVersion(roomId)+1,userId,
            request.requestId(),requestFingerprint,preview.previewToken(),preview.members(),preview.transfers()));
        if(state==null) states.save(RoomSettlementState.confirmed(room,snapshot.getId()));
        else state.confirm(snapshot.getId());
        return new Confirmation(view(snapshot),true);
    }
    public SnapshotView detail(Long roomId,Long snapshotId,Long userId) {
        authorization.checkMemberForUpdate(roomId,userId);
        return view(snapshots.findByIdAndRoomId(snapshotId,roomId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND)));
    }
    public StateView reopen(Long roomId,Long snapshotId,Long userId) {
        Room room=authorization.checkMemberForUpdate(roomId,userId);
        if(!room.getOwner().getId().equals(userId))
            throw new SettlementRequestException(ErrorCode.FORBIDDEN,"FORBIDDEN","방장만 정산을 재개방할 수 있습니다.");
        var state=loadState(roomId);
        var snapshot=snapshots.findByIdAndRoomId(snapshotId,roomId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        entityManager.flush();entityManager.refresh(snapshot);
        if(state!=null && "OPEN".equals(state.getStatus()) && "REOPENED".equals(snapshot.getStatus())
            && snapshot.getVersion()==snapshots.latestVersion(roomId)) return new StateView(roomId,"OPEN",null);
        if(state==null || !"CONFIRMED".equals(state.getStatus()) || !snapshotId.equals(state.getCurrentSnapshotId())
            || !"CONFIRMED".equals(snapshot.getStatus()))
            throw conflict("SETTLEMENT_VERSION_CONFLICT","현재 확정된 정산만 재개방할 수 있습니다.");
        snapshot.reopen(userId);state.reopen();
        return new StateView(roomId,"OPEN",null);
    }
    private RoomSettlementState loadState(Long roomId) {
        var state=states.findById(roomId).orElse(null);
        if(state!=null) {entityManager.flush();entityManager.refresh(state);}
        if(state==null && snapshots.latestVersion(roomId)!=0)
            throw new IllegalStateException("Settlement history exists without room state");
        if(state!=null && !Set.of("OPEN","CONFIRMED").contains(state.getStatus()))
            throw new IllegalStateException("Unknown settlement state");
        return state;
    }
    private PreviewView previewLocked(Room room,Long userId,RoomSettlementState state) {
        var input=inputs.read(room,snapshots.latestVersion(room.getId()),state);
        var result=calculation.calculate(room.getId(),userId);
        List<SettlementService.SettlementIssue> issues=new ArrayList<>(result.issues());issues.addAll(input.issues());
        var ordered=result.members().stream().sorted(Comparator.comparing(SettlementService.MemberSettlement::memberId)).toList();
        return new PreviewView(room.getId(),room.getName(),ordered,result.ready()?SettlementTransferCalculator.calculate(ordered):List.of(),
            input.token(),result.ready() && issues.isEmpty(),List.copyOf(issues));
    }
    private SnapshotView view(SettlementSnapshot snapshot) {
        return new SnapshotView(snapshot.getId(),snapshot.getRoom().getId(),snapshot.getRoomName(),snapshot.getVersion(),snapshot.getStatus(),
            snapshot.getConfirmedByUserId(),snapshot.getConfirmedAt(),snapshot.getReopenedByUserId(),snapshot.getReopenedAt(),
            snapshot.getMembers().stream().map(m->new MemberView(m.getMemberId(),m.getDisplayName(),m.getPaid(),m.getBurden(),m.getNet())).toList(),
            snapshot.getTransfers().stream().map(t->new TransferView(t.getId(),t.getFromMemberId(),t.getToMemberId(),t.getAmount(),t.getStatus(),t.getUpdatedByUserId(),t.getUpdatedAt())).toList());
    }
    private static SettlementRequestException conflict(String reason,String message) {return new SettlementRequestException(ErrorCode.CONFLICT,reason,message);}
    public record ConfirmRequest(String requestId,String previewToken) { }
    public record StateView(Long roomId,String status,Long currentSnapshotId) { }
    public record PreviewView(Long roomId,String roomName,List<SettlementService.MemberSettlement> members,
        List<SettlementTransferCalculator.Transfer> transfers,String previewToken,boolean ready,List<SettlementService.SettlementIssue> issues) { }
    public record MemberView(Long memberId,String displayName,long paid,long burden,long net) { }
    public record TransferView(Long transferId,Long fromMemberId,Long toMemberId,long amount,String status,Long updatedByUserId,LocalDateTime updatedAt) { }
    public record SnapshotView(Long snapshotId,Long roomId,String roomName,long version,String status,Long confirmedByUserId,
        LocalDateTime confirmedAt,Long reopenedByUserId,LocalDateTime reopenedAt,List<MemberView> members,List<TransferView> transfers) { }
    public record Confirmation(SnapshotView snapshot,boolean created) { }
}
