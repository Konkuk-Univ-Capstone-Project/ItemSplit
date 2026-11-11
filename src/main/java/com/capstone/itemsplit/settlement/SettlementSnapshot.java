package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.room.Room;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name="settlement_snapshots", uniqueConstraints={
    @UniqueConstraint(name="uk_snapshot_room_version", columnNames={"room_id","version"}),
    @UniqueConstraint(name="uk_snapshot_room_request", columnNames={"room_id","request_id"})})
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementSnapshot {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="room_id",nullable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private Room room;
    @Column(nullable=false,updatable=false) private String roomName;
    @Column(nullable=false,updatable=false) private long version;
    @Column(nullable=false,length=20) private String status;
    @Column(nullable=false,updatable=false) private Long confirmedByUserId;
    @Column(nullable=false,updatable=false) private LocalDateTime confirmedAt;
    private Long reopenedByUserId;
    private LocalDateTime reopenedAt;
    @Column(nullable=false,updatable=false,length=64) private String requestId;
    @Column(nullable=false,updatable=false,length=64) private String requestFingerprint;
    @Column(nullable=false,updatable=false,length=64) private String inputFingerprint;
    @OneToMany(mappedBy="snapshot",cascade=CascadeType.ALL) @OrderBy("memberId ASC")
    private List<SettlementSnapshotMember> members=new ArrayList<>();
    @OneToMany(mappedBy="snapshot",cascade=CascadeType.ALL) @OrderBy("id ASC")
    private List<SettlementTransfer> transfers=new ArrayList<>();
    void reopen(Long userId) {
        status="REOPENED";reopenedByUserId=userId;
        reopenedAt=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    static SettlementSnapshot create(Room room,long version,Long userId,String requestId,
        String requestFingerprint,String inputFingerprint,List<SettlementService.MemberSettlement> members,
        List<SettlementTransferCalculator.Transfer> transfers) {
        SettlementSnapshot snapshot=new SettlementSnapshot();
        snapshot.room=room;snapshot.roomName=room.getName();snapshot.version=version;snapshot.status="CONFIRMED";
        snapshot.confirmedByUserId=userId;snapshot.confirmedAt=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);snapshot.requestId=requestId;
        snapshot.requestFingerprint=requestFingerprint;snapshot.inputFingerprint=inputFingerprint;
        members.stream().sorted(Comparator.comparing(SettlementService.MemberSettlement::memberId))
            .forEach(m->snapshot.members.add(SettlementSnapshotMember.create(snapshot,m)));
        transfers.forEach(t->snapshot.transfers.add(SettlementTransfer.create(snapshot,t)));
        return snapshot;
    }
}
