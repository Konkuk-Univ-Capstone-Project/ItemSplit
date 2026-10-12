package com.capstone.itemsplit.ocr;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OcrDraftRepository extends JpaRepository<OcrDraft,Long> {
    boolean existsByReceiptIdAndStatus(Long receiptId,String status);
    java.util.Optional<OcrDraft> findByJobId(Long jobId);
}
