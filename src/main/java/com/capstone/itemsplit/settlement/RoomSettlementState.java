package com.capstone.itemsplit.settlement;
import com.capstone.itemsplit.room.Room;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
@Entity @Table(name="room_settlement_states") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class RoomSettlementState {
    @Id private Long roomId;
    @MapsId @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="room_id")
    @OnDelete(action=OnDeleteAction.CASCADE) private Room room;
    @Column(nullable=false,length=20) private String status;
    private Long currentSnapshotId;
    static RoomSettlementState confirmed(Room room,Long snapshotId) {
        RoomSettlementState state=new RoomSettlementState();state.room=room;
        state.status="CONFIRMED";state.currentSnapshotId=snapshotId;return state;
    }
    void reopen() {status="OPEN";currentSnapshotId=null;}
    void confirm(Long snapshotId) {status="CONFIRMED";currentSnapshotId=snapshotId;}
}
