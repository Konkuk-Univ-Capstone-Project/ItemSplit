package com.capstone.itemsplit.item;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {

	List<Item> findAllByReceiptId(Long receiptId);

	List<Item> findAllByReceiptIdIn(Collection<Long> receiptIds);

	@Query("select i from Item i where i.id = :id and i.receipt.id = :receiptId")
	Optional<Item> findByIdAndReceiptId(@Param("id") Long id, @Param("receiptId") Long receiptId);

	@Query("select i from Item i join fetch i.receipt where i.id = :id")
	Optional<Item> findByIdWithReceipt(@Param("id") Long id);

	@Modifying(clearAutomatically = true)
	@Query("delete from Item i where i.receipt.id = :receiptId")
	void deleteAllByReceiptId(@Param("receiptId") Long receiptId);

}
