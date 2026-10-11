package com.capstone.itemsplit.ocr;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OcrJobImageRepository extends JpaRepository<OcrJobImage,Long> {
    java.util.List<OcrJobImage> findAllByJobIdOrderBySortOrder(Long jobId);
}
