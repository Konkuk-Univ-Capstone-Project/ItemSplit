package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.domain.assignment.Assignment;
import com.capstone.itemsplit.domain.assignment.AssignmentRepository;
import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.item.ItemRepository;
import com.capstone.itemsplit.domain.receipt.Receipt;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.roommember.RoomMember;
import com.capstone.itemsplit.domain.roommember.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementService {

	private final RoomAuthorizationService roomAuthorizationService;
	private final RoomMemberRepository roomMemberRepository;
	private final ReceiptRepository receiptRepository;
	private final ItemRepository itemRepository;
	private final AssignmentRepository assignmentRepository;

	public SettlementResult calculate(Long roomId, Long userId) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);

		List<RoomMember> members = roomMemberRepository.findAllByRoomId(roomId);
		List<Receipt> receipts = receiptRepository.findAllByRoomId(roomId);

		if (receipts.isEmpty()) {
			return SettlementResult.empty(room, members);
		}

		List<Long> receiptIds = receipts.stream().map(Receipt::getId).toList();
		List<Item> items = itemRepository.findAllByReceiptIdIn(receiptIds);

		if (items.isEmpty()) {
			return SettlementResult.empty(room, members);
		}

		List<Long> itemIds = items.stream().map(Item::getId).toList();
		List<Assignment> assignments = assignmentRepository.findAllByItemIdInWithUser(itemIds);

		// itemId → assignments
		Map<Long, List<Assignment>> assignmentsByItemId = assignments.stream()
			.collect(Collectors.groupingBy(a -> a.getItem().getId()));

		// itemId → item
		Map<Long, Item> itemById = items.stream()
			.collect(Collectors.toMap(Item::getId, i -> i));

		// receiptId → receipt
		Map<Long, Receipt> receiptById = receipts.stream()
			.collect(Collectors.toMap(Receipt::getId, r -> r));

		// 멤버별 부담액
		Map<Long, Long> burden = new HashMap<>();
		for (RoomMember member : members) {
			burden.put(member.getUser().getId(), 0L);
		}

		// 결제자별 지불액
		Map<Long, Long> paid = new HashMap<>();

		for (Item item : items) {
			List<Assignment> itemAssignments = assignmentsByItemId.get(item.getId());
			if (itemAssignments == null || itemAssignments.isEmpty()) {
				continue; // 배정 없는 품목은 정산 제외
			}

			long total = (long) item.getPrice() * item.getQuantity();
			int count = itemAssignments.size();
			long perPerson = total / count;
			long remainder = total % count;

			for (Assignment assignment : itemAssignments) {
				long memberId = assignment.getUser().getId();
				burden.merge(memberId, perPerson, Long::sum);
			}

			// 나머지는 품목 참여자 중 seeded random으로 선택 (roomId+itemId 시드 → 조회마다 동일)
			if (remainder > 0) {
				int pickedIndex = new Random(roomId * 1_000_003L + item.getId()).nextInt(count);
				long pickedId = itemAssignments.get(pickedIndex).getUser().getId();
				burden.merge(pickedId, remainder, Long::sum);
			}
		}

		// 결제자 지불액 계산 (배정된 품목 합산만)
		for (Item item : items) {
			List<Assignment> itemAssignments = assignmentsByItemId.get(item.getId());
			if (itemAssignments == null || itemAssignments.isEmpty()) {
				continue;
			}
			Receipt receipt = receiptById.get(item.getReceipt().getId());
			if (receipt.getPayer() == null) {
				continue;
			}
			long payerId = receipt.getPayer().getId();
			long total = (long) item.getPrice() * item.getQuantity();
			paid.merge(payerId, total, Long::sum);
		}

		List<MemberSettlement> memberSettlements = members.stream()
			.map(member -> {
				long memberId = member.getUser().getId();
				long memberBurden = burden.getOrDefault(memberId, 0L);
				long memberPaid = paid.getOrDefault(memberId, 0L);
				return new MemberSettlement(
					memberId,
					member.getUser().getNickname(),
					memberBurden,
					memberPaid,
					memberPaid - memberBurden
				);
			})
			.toList();

		return new SettlementResult(roomId, room.getName(), memberSettlements);
	}

	public record SettlementResult(
		Long roomId,
		String roomName,
		List<MemberSettlement> members
	) {
		static SettlementResult empty(Room room, List<RoomMember> members) {
			List<MemberSettlement> memberSettlements = members.stream()
				.map(m -> new MemberSettlement(
					m.getUser().getId(),
					m.getUser().getNickname(),
					0L, 0L, 0L
				))
				.toList();
			return new SettlementResult(room.getId(), room.getName(), memberSettlements);
		}
	}

	public record MemberSettlement(
		Long userId,
		String nickname,
		long burden,    // 이 멤버가 부담해야 할 금액
		long paid,      // 이 멤버가 결제자로서 낸 금액
		long net        // 양수: 받아야 할 금액, 음수: 내야 할 금액
	) {
	}

}
