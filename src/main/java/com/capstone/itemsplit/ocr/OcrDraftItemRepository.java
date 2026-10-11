package com.capstone.itemsplit.ocr;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OcrDraftItemRepository extends JpaRepository<OcrDraftItem,Long> {
    java.util.List<OcrDraftItem> findAllByDraftIdOrderBySortOrder(Long draftId);
}
