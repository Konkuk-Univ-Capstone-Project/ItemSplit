package com.capstone.itemsplit.domain.receipt;

import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.user.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReceiptSourceType sourceType;

    private String storedPath;

    private String originalFilename;

    private String contentType;

    private Long fileSize;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_id")
    private User payer;

    private Integer declaredTotal;

    private LocalDate purchasedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Receipt(
        Room room,
        String name,
        ReceiptSourceType sourceType,
        String storedPath,
        String originalFilename,
        String contentType,
        Long fileSize
    ) {
        this.room = room;
        this.name = name;
        this.sourceType = sourceType;
        this.storedPath = storedPath;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    public static Receipt createImageUpload(
        Room room,
        String name,
        String storedPath,
        String originalFilename,
        String contentType,
        long fileSize
    ) {
        return new Receipt(
            room,
            name,
            ReceiptSourceType.IMAGE_UPLOAD,
            storedPath,
            originalFilename,
            contentType,
            fileSize
        );
    }

    public static Receipt createManual(Room room, String name, User payer, Integer declaredTotal, LocalDate purchasedAt) {
        Receipt receipt = new Receipt(room, name, ReceiptSourceType.MANUAL, null, null, null, null);
        receipt.payer = payer;
        receipt.declaredTotal = declaredTotal;
        receipt.purchasedAt = purchasedAt;
        return receipt;
    }

    public void update(String name, User payer, Integer declaredTotal, LocalDate purchasedAt) {
        this.name = name;
        this.payer = payer;
        this.declaredTotal = declaredTotal;
        this.purchasedAt = purchasedAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
