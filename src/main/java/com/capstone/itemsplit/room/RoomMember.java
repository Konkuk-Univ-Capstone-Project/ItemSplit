package com.capstone.itemsplit.room;

import com.capstone.itemsplit.user.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "room_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private RoomMember(Room room, User user) {
        this.room = room;
        this.user = user;
    }

    public static RoomMember create(Room room, User user) {
        return new RoomMember(room, user);
    }
}
