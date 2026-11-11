package com.capstone.itemsplit.settlement;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
@Entity @Table(name="settlement_snapshot_members",uniqueConstraints=@UniqueConstraint(columnNames={"snapshot_id","member_id"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementSnapshotMember {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="snapshot_id",nullable=false,updatable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private SettlementSnapshot snapshot;
    @Column(nullable=false,updatable=false) private Long memberId;
    @Column(nullable=false,updatable=false) private String displayName;
    @Column(nullable=false,updatable=false) private long paid;
    @Column(nullable=false,updatable=false) private long burden;
    @Column(nullable=false,updatable=false) private long net;
    static SettlementSnapshotMember create(SettlementSnapshot snapshot,SettlementService.MemberSettlement source) {
        SettlementSnapshotMember row=new SettlementSnapshotMember();row.snapshot=snapshot;row.memberId=source.memberId();
        row.displayName=source.nickname();row.paid=source.paid();row.burden=source.burden();row.net=source.net();return row;
    }
}
