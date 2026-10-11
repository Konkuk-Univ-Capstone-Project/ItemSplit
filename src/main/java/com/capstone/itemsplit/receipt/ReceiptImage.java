package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.storage.StorageService;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "receipt_images", uniqueConstraints = @UniqueConstraint(name = "uk_receipt_images_receipt_order", columnNames = {"receipt_id", "sort_order"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReceiptImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 512)
    private String storedPath;

    private String originalFilename;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ReceiptImage(Receipt receipt, int sortOrder, String storedPath, String originalFilename, String contentType, long fileSize) {
        this.receipt = receipt;
        this.sortOrder = sortOrder;
        this.storedPath = storedPath;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    public static ReceiptImage create(Receipt receipt, int sortOrder, StorageService.StoredFile file) {
        return new ReceiptImage(receipt, sortOrder, file.storedPath(), file.originalFilename(), file.contentType(), file.size());
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
