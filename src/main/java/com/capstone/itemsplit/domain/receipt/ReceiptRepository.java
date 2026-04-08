package com.capstone.itemsplit.domain.receipt;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

	@Query("""
		select receipt
		from Receipt receipt
		left join fetch receipt.payer payer
		where receipt.room.id = :roomId
		order by receipt.id desc
		""")
	List<Receipt> findAllByRoomId(@Param("roomId") Long roomId);

}
