package com.capstone.itemsplit.settlement;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
@Entity @Table(name="settlement_transfers") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementTransfer {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="snapshot_id",nullable=false,updatable=false)
    @OnDelete(action=OnDeleteAction.CASCADE) private SettlementSnapshot snapshot;
    @Column(nullable=false,updatable=false) private Long fromMemberId;
    @Column(nullable=false,updatable=false) private Long toMemberId;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,length=20) private String status;
    private Long updatedByUserId;
    private LocalDateTime updatedAt;
    void setCompleted(boolean completed,Long actor) {
        String target=completed?"COMPLETED":"PENDING";
        if(target.equals(status)) return;
        status=target;updatedByUserId=actor;
        updatedAt=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    static SettlementTransfer create(SettlementSnapshot snapshot,SettlementTransferCalculator.Transfer source) {
        SettlementTransfer row=new SettlementTransfer();row.snapshot=snapshot;row.fromMemberId=source.fromMemberId();
        row.toMemberId=source.toMemberId();row.amount=source.amount();row.status="PENDING";return row;
    }
}
