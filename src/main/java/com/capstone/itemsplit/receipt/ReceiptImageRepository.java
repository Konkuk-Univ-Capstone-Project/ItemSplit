package com.capstone.itemsplit.receipt;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptImageRepository extends JpaRepository<ReceiptImage, Long> {

    @Query("select i from ReceiptImage i where i.receipt.id = :receiptId order by i.sortOrder asc")
    List<ReceiptImage> findAllByReceiptId(@Param("receiptId") Long receiptId);

    @Query("select i from ReceiptImage i where i.receipt.id in :receiptIds")
    List<ReceiptImage> findAllByReceiptIdIn(@Param("receiptIds") Collection<Long> receiptIds);

    @Query("select count(i) from ReceiptImage i where i.receipt.id = :receiptId")
    long countByReceiptId(@Param("receiptId") Long receiptId);

    @Query("select coalesce(max(i.sortOrder), 0) from ReceiptImage i where i.receipt.id = :receiptId")
    int findMaxSortOrderByReceiptId(@Param("receiptId") Long receiptId);

    @Modifying(clearAutomatically = true)
    @Query("delete from ReceiptImage i where i.receipt.id = :receiptId")
    void deleteAllByReceiptId(@Param("receiptId") Long receiptId);

    @Modifying(clearAutomatically = true)
    @Query("delete from ReceiptImage i where i.receipt.id in :receiptIds")
    void deleteAllByReceiptIdIn(@Param("receiptIds") Collection<Long> receiptIds);

}
