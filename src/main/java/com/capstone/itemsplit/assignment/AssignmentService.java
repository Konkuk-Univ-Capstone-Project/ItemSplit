package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssignmentService {

	private final AssignmentRepository assignmentRepository;
	private final ItemRepository itemRepository;
	private final ReceiptRepository receiptRepository;
	private final UserRepository userRepository;
	private final RoomMemberRepository roomMemberRepository;
	private final RoomAuthorizationService roomAuthorizationService;

	public AssigneesResult getAssignees(Long roomId, Long receiptId, Long itemId, Long requesterId) {
		Item item = validateRequestScope(roomId, receiptId, itemId, requesterId);
		return AssigneesResult.from(item, assignmentRepository.findAllByItemId(itemId));
	}

	@Transactional
	public AssigneesResult replaceAssignees(
		Long roomId,
		Long receiptId,
		Long itemId,
		Long requesterId,
		List<Long> memberIds
	) {
		Item item = validateRequestScope(roomId, receiptId, itemId, requesterId);
		LinkedHashSet<Long> uniqueMemberIds = new LinkedHashSet<>(memberIds);

		if (!uniqueMemberIds.isEmpty()) {
			validateUsersExist(uniqueMemberIds);
		}

		List<RoomMember> roomMembers = uniqueMemberIds.isEmpty()
			? List.of()
			: roomMemberRepository.findAllByRoomIdAndUserIdIn(roomId, uniqueMemberIds);

		if (roomMembers.size() != uniqueMemberIds.size()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "All assignees must be members of this room.");
		}

		List<Assignment> existingAssignments = assignmentRepository.findAllByItemId(itemId);
		if (!existingAssignments.isEmpty()) {
			assignmentRepository.deleteAllInBatch(existingAssignments);
		}

		if (!roomMembers.isEmpty()) {
			Map<Long, User> userById = roomMembers.stream()
				.map(RoomMember::getUser)
				.collect(java.util.stream.Collectors.toMap(User::getId, Function.identity()));

			List<Assignment> assignments = uniqueMemberIds.stream()
				.map(memberId -> Assignment.create(item, userById.get(memberId)))
				.toList();

			assignmentRepository.saveAll(assignments);
		}

		return AssigneesResult.from(item, assignmentRepository.findAllByItemId(itemId));
	}

	private Item validateRequestScope(Long roomId, Long receiptId, Long itemId, Long requesterId) {
		roomAuthorizationService.checkMember(roomId, requesterId);

		Receipt receipt = receiptRepository.findById(receiptId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
		if (!receipt.getRoom().getId().equals(roomId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found in this room.");
		}

		Item item = itemRepository.findById(itemId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Item was not found."));
		if (!item.getReceipt().getId().equals(receiptId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Item was not found in this receipt.");
		}

		return item;
	}

	private void validateUsersExist(LinkedHashSet<Long> memberIds) {
		List<User> users = userRepository.findAllById(memberIds);
		if (users.size() != memberIds.size()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "One or more members were not found.");
		}
	}

	public record AssigneesResult(
		Long roomId,
		Long receiptId,
		Long itemId,
		String itemName,
		List<AssigneeInfo> assignees
	) {

		private static AssigneesResult from(Item item, List<Assignment> assignments) {
			return new AssigneesResult(
				item.getReceipt().getRoom().getId(),
				item.getReceipt().getId(),
				item.getId(),
				item.getName(),
				assignments.stream()
					.map(assignment -> new AssigneeInfo(
						assignment.getUser().getId(),
						assignment.getUser().getEmail(),
						assignment.getUser().getNickname()
					))
					.toList()
			);
		}

	}

	public record AssigneeInfo(Long userId, String email, String nickname) {
	}

}
