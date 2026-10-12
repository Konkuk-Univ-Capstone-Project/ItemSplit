package com.capstone.itemsplit.ocr;

import com.capstone.itemsplit.common.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Daily reservations and spent calls are persisted, independently of the OCR job lifecycle. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OcrQuotaService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final OcrJobImageRepository images;
    private final Clock clock;
    private final int perReceiptDaily;
    private final int perUserDaily;

    public OcrQuotaService(JdbcTemplate jdbc, OcrJobImageRepository images,
            @Qualifier("ocrQuotaClock") Clock clock,
            @Value("${app.ocr.limit.per-receipt-daily:15}") int perReceiptDaily,
            @Value("${app.ocr.limit.per-user-daily:60}") int perUserDaily) {
        if (perReceiptDaily < 1 || perUserDaily < 1) {
            throw new IllegalArgumentException("OCR daily limits must be positive");
        }
        this.jdbc = jdbc;
        this.images = images;
        this.clock = clock;
        this.perReceiptDaily = perReceiptDaily;
        this.perUserDaily = perUserDaily;
    }

    public LocalDate reserve(Long receiptId, Long userId, int count) {
        if (count < 1) throw new IllegalArgumentException("Reservation count must be positive");
        LocalDate date = clock.instant().atZone(SEOUL).toLocalDate();
        // Keep a fixed lock order. Failure in either scope rolls back BOTH counters and the job.
        increment(key("RECEIPT", receiptId, date), count, perReceiptDaily,
            "OCR_RECEIPT_DAILY_LIMIT", "영수증의 오늘 OCR 호출 한도를 초과했습니다.");
        increment(key("USER", userId, date), count, perUserDaily,
            "OCR_USER_DAILY_LIMIT", "사용자의 오늘 OCR 호출 한도를 초과했습니다.");
        return date;
    }

    private void increment(String key, int count, int limit, String reason, String message) {
        // Conflict-safe first use and conditional increments also serialize requests from other servers.
        jdbc.update("INSERT INTO ocr_daily_usage (quota_key, used_count) VALUES (?, 0) ON CONFLICT DO NOTHING", key);
        int changed = jdbc.update("UPDATE ocr_daily_usage SET used_count = used_count + ? "
            + "WHERE quota_key = ? AND used_count <= ?", count, key, limit - count);
        if (changed != 1) throw new OcrRequestException(ErrorCode.TOO_MANY_REQUESTS, reason, message);
    }

    /** Caller holds the room lock, so each image reservation can only be returned once. */
    public void releaseUncalled(OcrJobImage image) { releaseUncalledImages(List.of(image)); }

    /** Run before bulk/cascade deletion, while the owning room is locked. */
    public void releaseUncalledForReceipts(List<Long> receiptIds) {
        if (receiptIds.isEmpty()) return;
        releaseUncalledImages(images.findUncalledByReceiptIds(receiptIds));
    }

    private void releaseUncalledImages(List<OcrJobImage> candidates) {
        var uncalled = candidates.stream().filter(image -> image.isQuotaReserved()
            && !image.isQuotaInvoked() && image.getQuotaDate() != null).toList();
        var refunds = new TreeMap<String, Integer>();
        for (var image : uncalled) {
            var job = image.getJob();
            refunds.merge(key("RECEIPT", job.getReceipt().getId(), image.getQuotaDate()), 1, Integer::sum);
            refunds.merge(key("USER", job.getRequestedByUserId(), image.getQuotaDate()), 1, Integer::sum);
        }
        // Receipt keys precede user keys, and multi-user room deletion locks every scope consistently.
        refunds.forEach((key, count) -> {
            int changed = jdbc.update("UPDATE ocr_daily_usage SET used_count = used_count - ? "
                + "WHERE quota_key = ? AND used_count >= ?", count, key, count);
            if (changed != 1) throw new IllegalStateException("Missing OCR quota reservation");
        });
        uncalled.forEach(OcrJobImage::releaseQuota);
    }

    private static String key(String scope, Long id, LocalDate date) { return scope + ":" + id + ":" + date; }
}
