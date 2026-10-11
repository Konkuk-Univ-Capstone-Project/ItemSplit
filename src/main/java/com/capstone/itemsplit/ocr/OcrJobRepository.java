package com.capstone.itemsplit.ocr;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OcrJobRepository extends JpaRepository<OcrJob,Long> {
    boolean existsByReceiptId(Long receiptId);
    @org.springframework.data.jpa.repository.Query("select j.receipt.room.id from OcrJob j where j.id = :id")
    java.util.Optional<Long> findRoomId(@org.springframework.data.repository.query.Param("id") Long id);
    java.util.Optional<OcrJob> findByReceiptId(Long receiptId);
    java.util.List<OcrJob> findAllByStatusIn(java.util.Collection<OcrJob.Status> states);
}
