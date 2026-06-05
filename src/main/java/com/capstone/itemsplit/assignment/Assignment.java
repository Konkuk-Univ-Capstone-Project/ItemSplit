package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.room.RoomMember;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "room_member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_member_id")
    private RoomMember roomMember;

    private Assignment(Item item, RoomMember roomMember) {
        this.item = item;
        this.roomMember = roomMember;
    }

    public static Assignment create(Item item, RoomMember roomMember) {
        return new Assignment(item, roomMember);
    }
}
