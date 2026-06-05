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
    @JoinColumn(name = "user_id")
    private User user;

    @Column(length = 50)
    private String displayName;

    private RoomMember(Room room, User user, String displayName) {
        this.room = room;
        this.user = user;
        this.displayName = displayName;
    }

    public static RoomMember create(Room room, User user) {
        return new RoomMember(room, user, user.getNickname());
    }

    public static RoomMember create(Room room, User user, String displayName) {
        return new RoomMember(room, user, displayName);
    }

    public static RoomMember createManual(Room room, String displayName) {
        return new RoomMember(room, null, displayName);
    }

    public boolean isLinkedUser() {
        return user != null;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (user != null) {
            return user.getNickname();
        }
        return "이름 없는 멤버";
    }

    public void claim(User user, String displayName) {
        this.user = user;
        this.displayName = displayName;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }
}
