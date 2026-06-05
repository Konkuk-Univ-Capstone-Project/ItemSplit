package com.capstone.itemsplit.assignment;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
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
		return AssigneesResult.from(roomId, receiptId, item, assignmentRepository.findAllByItemId(itemId));
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

		List<RoomMember> roomMembers = resolveAssigneeMembers(roomId, uniqueMemberIds);

		List<Assignment> existingAssignments = assignmentRepository.findAllByItemId(itemId);
		if (!existingAssignments.isEmpty()) {
			assignmentRepository.deleteAllInBatch(existingAssignments);
		}

		if (!roomMembers.isEmpty()) {
			List<Assignment> assignments = roomMembers.stream()
				.map(roomMember -> Assignment.create(item, roomMember))
				.toList();

			assignmentRepository.saveAll(assignments);
		}

		return AssigneesResult.from(roomId, receiptId, item, assignmentRepository.findAllByItemId(itemId));
	}

	private Item validateRequestScope(Long roomId, Long receiptId, Long itemId, Long requesterId) {
		roomAuthorizationService.checkMember(roomId, requesterId);

		Receipt receipt = receiptRepository.findByIdWithRoom(receiptId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
		if (!receipt.getRoom().getId().equals(roomId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found in this room.");
		}

		Item item = itemRepository.findByIdWithReceipt(itemId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Item was not found."));
		if (!item.getReceipt().getId().equals(receiptId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Item was not found in this receipt.");
		}

		return item;
	}

	private List<RoomMember> resolveAssigneeMembers(Long roomId, LinkedHashSet<Long> memberIds) {
		if (memberIds.isEmpty()) {
			return List.of();
		}

		List<RoomMember> membersById = roomMemberRepository.findAllByRoomIdAndIdIn(roomId, memberIds);
		if (membersById.size() == memberIds.size()) {
			Map<Long, RoomMember> memberById = membersById.stream()
				.collect(java.util.stream.Collectors.toMap(RoomMember::getId, Function.identity()));
			return memberIds.stream().map(memberById::get).toList();
		}

		List<RoomMember> membersByUserId = roomMemberRepository.findAllByRoomIdAndUserIdIn(roomId, memberIds);
		if (membersByUserId.size() == memberIds.size()) {
			Map<Long, RoomMember> memberByUserId = membersByUserId.stream()
				.collect(java.util.stream.Collectors.toMap(member -> member.getUser().getId(), Function.identity()));
			return memberIds.stream().map(memberByUserId::get).toList();
		}

		long existingUserCount = userRepository.countByIdIn(memberIds);
		if (existingUserCount != memberIds.size()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "One or more members were not found.");
		}

		throw new ApiException(ErrorCode.VALIDATION_ERROR, "All assignees must be members of this room.");
	}

	public record AssigneesResult(
		Long roomId,
		Long receiptId,
		Long itemId,
		String itemName,
		List<AssigneeInfo> assignees
	) {

		private static AssigneesResult from(Long roomId, Long receiptId, Item item, List<Assignment> assignments) {
			return new AssigneesResult(
				roomId,
				receiptId,
				item.getId(),
				item.getName(),
				assignments.stream()
					.map(assignment -> new AssigneeInfo(
						assignment.getRoomMember().getId(),
						assignment.getRoomMember().getUser() != null ? assignment.getRoomMember().getUser().getId() : null,
						assignment.getRoomMember().getUser() != null ? assignment.getRoomMember().getUser().getEmail() : null,
						assignment.getRoomMember().getDisplayName(),
						assignment.getRoomMember().isLinkedUser()
					))
					.toList()
			);
		}

	}

	public record AssigneeInfo(Long memberId, Long userId, String email, String nickname, boolean linked) {
	}

}
