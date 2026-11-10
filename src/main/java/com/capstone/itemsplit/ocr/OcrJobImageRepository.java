package com.capstone.itemsplit.ocr;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OcrJobImageRepository extends JpaRepository<OcrJobImage,Long> {
    java.util.List<OcrJobImage> findAllByJobIdOrderBySortOrder(Long jobId);
    @org.springframework.data.jpa.repository.Query("select i from OcrJobImage i "
        + "where i.job.receipt.id in :receiptIds and i.quotaReserved = true and i.quotaInvoked = false order by i.id")
    java.util.List<OcrJobImage> findUncalledByReceiptIds(
        @org.springframework.data.repository.query.Param("receiptIds") java.util.List<Long> receiptIds);
}
