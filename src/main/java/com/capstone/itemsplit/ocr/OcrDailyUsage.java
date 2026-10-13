package com.capstone.itemsplit.ocr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** No foreign keys: deleting a receipt or account must not reset already spent usage. */
@Entity
@Table(name = "ocr_daily_usage")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OcrDailyUsage {
    @Id @Column(length = 80, nullable = false)
    private String quotaKey;
    @Column(nullable = false)
    private int usedCount;
}
