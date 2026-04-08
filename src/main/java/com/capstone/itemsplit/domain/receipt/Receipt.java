package com.capstone.itemsplit.domain.receipt;

import com.capstone.itemsplit.domain.room.Room;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String storedPath;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Receipt(
        Room room,
        String name,
        String storedPath,
        String originalFilename,
        String contentType,
        long fileSize
    ) {
        this.room = room;
        this.name = name;
        this.storedPath = storedPath;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    public static Receipt create(
        Room room,
        String name,
        String storedPath,
        String originalFilename,
        String contentType,
        long fileSize
    ) {
        return new Receipt(room, name, storedPath, originalFilename, contentType, fileSize);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
