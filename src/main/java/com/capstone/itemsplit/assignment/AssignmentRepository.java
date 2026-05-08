package com.capstone.itemsplit.assignment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	@Query("""
		select assignment
		from Assignment assignment
		where assignment.item.id in :itemIds
		""")
	List<Assignment> findAllByItemIdIn(@Param("itemIds") Collection<Long> itemIds);

	@Query("""
		select assignment
		from Assignment assignment
		join fetch assignment.user user
		where assignment.item.id in :itemIds
		order by assignment.item.id asc, assignment.id asc
		""")
	List<Assignment> findAllByItemIdInWithUser(@Param("itemIds") Collection<Long> itemIds);

	@Modifying(clearAutomatically = true)
	@Query("delete from Assignment a where a.item.id in :itemIds")
	void deleteAllByItemIdIn(@Param("itemIds") Collection<Long> itemIds);

}
