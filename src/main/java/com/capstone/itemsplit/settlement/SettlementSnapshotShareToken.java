package com.capstone.itemsplit.settlement;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
/** Persistence shape only; issuance and public access belong to a later stage. */
@Entity @Table(name="settlement_snapshot_share_tokens") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementSnapshotShareToken {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="snapshot_id",nullable=false,unique=true)
    @OnDelete(action=OnDeleteAction.CASCADE) private SettlementSnapshot snapshot;
    @Column(nullable=false,unique=true,length=64) private String tokenHash;
    @Column(nullable=false) private LocalDateTime createdAt;
    @Column(nullable=false) private LocalDateTime expiresAt;
}
