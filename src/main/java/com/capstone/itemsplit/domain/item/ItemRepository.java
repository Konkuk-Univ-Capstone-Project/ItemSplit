package com.capstone.itemsplit.domain.item;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {

	List<Item> findAllByReceiptId(Long receiptId);

	List<Item> findAllByReceiptIdIn(Collection<Long> receiptIds);

	@Modifying(clearAutomatically = true)
	@Query("delete from Item i where i.receipt.id = :receiptId")
	void deleteAllByReceiptId(@Param("receiptId") Long receiptId);

}
