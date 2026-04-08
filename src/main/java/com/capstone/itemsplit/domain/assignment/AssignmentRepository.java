package com.capstone.itemsplit.domain.assignment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

	@Query("""
		select assignment
		from Assignment assignment
		join fetch assignment.user user
		where assignment.item.id = :itemId
		order by assignment.id asc
		""")
	List<Assignment> findAllByItemId(@Param("itemId") Long itemId);

}
