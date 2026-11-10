package com.capstone.itemsplit.item;

import com.capstone.itemsplit.receipt.Receipt;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean excludedFromSettlement;

    @Column(unique=true)
    private Long ocrDraftItemId;

    public void recordOcrSource(Long draftItemId) { this.ocrDraftItemId=draftItemId; }

    public void requireOcrEditor(Long userId) {
        if (ocrDraftItemId != null && (receipt.getCreatedBy() == null || !receipt.getCreatedBy().getId().equals(userId)))
            throw new com.capstone.itemsplit.common.exception.ApiException(
                com.capstone.itemsplit.common.exception.ErrorCode.FORBIDDEN,"OCR 품목은 영수증 작성자만 수정·삭제할 수 있습니다.");
    }

    public void setExcludedFromSettlement(boolean excludedFromSettlement) {
        this.excludedFromSettlement = excludedFromSettlement;
    }

    private Item(Receipt receipt, String name, long price, int quantity) {
        this.receipt = receipt;
        this.name = name;
        this.price = price;
        this.quantity = quantity;
    }

    public static Item create(Receipt receipt, String name, long price, int quantity) {
        return new Item(receipt, name, price, quantity);
    }

    public void update(String name, long price, int quantity) {
        this.name = name;
        this.price = price;
        this.quantity = quantity;
    }
}
