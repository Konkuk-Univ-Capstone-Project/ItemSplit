package com.capstone.itemsplit.domain.assignment;

import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "user_id"}))
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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private Assignment(Item item, User user) {
        this.item = item;
        this.user = user;
    }

    public static Assignment create(Item item, User user) {
        return new Assignment(item, user);
    }
}
