package com.capstone.itemsplit.receipt;

import java.util.List;
import java.util.Optional;
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

	@Query("select r from Receipt r where r.id = :id and r.room.id = :roomId")
	Optional<Receipt> findByIdAndRoomId(@Param("id") Long id, @Param("roomId") Long roomId);

	@Query("select count(r) > 0 from Receipt r where r.id = :id and r.room.id = :roomId")
	boolean existsByIdAndRoomId(@Param("id") Long id, @Param("roomId") Long roomId);

	@Query("select r from Receipt r join fetch r.room where r.id = :id")
	Optional<Receipt> findByIdWithRoom(@Param("id") Long id);

}
