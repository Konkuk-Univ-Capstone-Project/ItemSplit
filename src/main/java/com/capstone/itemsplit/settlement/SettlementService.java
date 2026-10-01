package com.capstone.itemsplit.settlement;

import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.common.MoneyPolicy;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SettlementService {

    private final RoomAuthorizationService roomAuthorizationService;
    private final RoomMemberRepository roomMemberRepository;
    private final ReceiptRepository receiptRepository;
    private final ItemRepository itemRepository;
    private final AssignmentRepository assignmentRepository;

    public SettlementResult calculate(Long roomId, Long userId) {
        return calculateAuthorized(roomAuthorizationService.checkMember(roomId, userId));
    }

    public SettlementResult calculateShared(Room room) {
        SettlementResult result = calculateAuthorized(room);
        if (!result.ready()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "정산 입력을 확인한 뒤 공유해 주세요. " + result.issues().getFirst().message());
        }
        return result;
    }

    private SettlementResult calculateAuthorized(Room room) {
        Long roomId = room.getId();
        List<RoomMember> members = roomMemberRepository.findAllByRoomId(roomId);
        List<Receipt> receipts = receiptRepository.findAllByRoomId(roomId);
        List<Item> items = receipts.isEmpty() ? List.of()
            : itemRepository.findAllByReceiptIdIn(receipts.stream().map(Receipt::getId).toList());
        List<Assignment> assignments = items.isEmpty() ? List.of()
            : assignmentRepository.findAllByItemIdInWithUser(items.stream().map(Item::getId).toList());
        Map<Long, List<Assignment>> assignmentsByItem = assignments.stream()
            .collect(Collectors.groupingBy(a -> a.getItem().getId()));
        List<SettlementIssue> issues = validate(members, receipts, items, assignmentsByItem);
        if (!issues.isEmpty()) {
            return new SettlementResult(roomId, room.getName(), List.of(), false, issues);
        }

        Map<Long, Long> burden = new HashMap<>();
        Map<Long, Long> paid = new HashMap<>();
        Map<Long, Receipt> receiptById = receipts.stream().collect(Collectors.toMap(Receipt::getId, r -> r));
        for (Item item : items) {
            if (item.isExcludedFromSettlement()) {
                continue;
            }
            List<Assignment> participants = new ArrayList<>(assignmentsByItem.get(item.getId()));
            // Canonical input order makes the seeded remainder stable after reassignment.
            participants.sort(Comparator.comparing(a -> a.getRoomMember().getId()));
            long total = item.getPrice() * item.getQuantity();
            long perPerson = total / participants.size();
            long remainder = total % participants.size();
            for (Assignment assignment : participants) {
                burden.merge(assignment.getRoomMember().getId(), perPerson, Long::sum);
            }
            Collections.shuffle(participants, new Random(roomId * 1_000_003L + item.getId()));
            for (int i = 0; i < remainder; i++) {
                burden.merge(participants.get(i).getRoomMember().getId(), 1L, Long::sum);
            }
            paid.merge(receiptById.get(item.getReceipt().getId()).getPayer().getId(), total, Long::sum);
        }
        List<MemberSettlement> results = members.stream().map(member -> {
            long memberBurden = burden.getOrDefault(member.getId(), 0L);
            long memberPaid = paid.getOrDefault(member.getId(), 0L);
            return new MemberSettlement(member.getId(), member.isLinkedUser() ? member.getUser().getId() : null,
                member.getDisplayName(), member.isLinkedUser(), memberBurden, memberPaid, memberPaid - memberBurden);
        }).toList();
        return new SettlementResult(roomId, room.getName(), results, true, List.of());
    }

    private List<SettlementIssue> validate(List<RoomMember> members, List<Receipt> receipts,
        List<Item> items, Map<Long, List<Assignment>> assignmentsByItem) {
        List<SettlementIssue> issues = new ArrayList<>();
        Set<Long> memberIds = members.stream().map(RoomMember::getId).collect(Collectors.toSet());
        Map<Long, List<Item>> itemsByReceipt = items.stream().collect(Collectors.groupingBy(i -> i.getReceipt().getId()));
        long roomTotal = 0;
        boolean roomLimitReported = false;
        for (Receipt receipt : receipts) {
            Long receiptId = receipt.getId();
            List<Item> receiptItems = itemsByReceipt.getOrDefault(receiptId, List.of());
            if (receiptItems.isEmpty()) {
                issues.add(new SettlementIssue("EMPTY_RECEIPT", receiptId, null, "영수증에 품목을 입력해 주세요."));
            }
            Long declaredTotal = receipt.getDeclaredTotal();
            if (declaredTotal == null) {
                issues.add(new SettlementIssue("MISSING_DECLARED_TOTAL", receiptId, null, "영수증 총액을 입력·확정해 주세요."));
            } else if (declaredTotal < 1 || declaredTotal > MoneyPolicy.RECEIPT_MAX) {
                issues.add(new SettlementIssue("INVALID_DECLARED_TOTAL", receiptId, null, "영수증 총액은 1원부터 1,000만 원까지 입력할 수 있습니다."));
            }
            long receiptTotal = 0;
            boolean validAmounts = true;
            boolean receiptLimitReported = false;
            boolean included = false;
            for (Item item : receiptItems) {
                long price = item.getPrice();
                int quantity = item.getQuantity();
                if (price < 1 || price > MoneyPolicy.PRICE_MAX || quantity < 1 || quantity > MoneyPolicy.QUANTITY_MAX
                    || price > MoneyPolicy.RECEIPT_MAX / quantity) {
                    validAmounts = false;
                    issues.add(new SettlementIssue("INVALID_ITEM_AMOUNT", receiptId, item.getId(), "품목 단가·수량·금액의 허용 범위를 확인해 주세요."));
                } else {
                    long itemTotal = price * quantity;
                    if (!receiptLimitReported && receiptTotal > MoneyPolicy.RECEIPT_MAX - itemTotal) {
                        receiptLimitReported = true;
                        issues.add(new SettlementIssue("RECEIPT_TOTAL_LIMIT", receiptId, null, "제외 품목을 포함한 영수증 품목 합계가 1,000만 원을 초과합니다."));
                    }
                    if (!receiptLimitReported) {
                        receiptTotal += itemTotal;
                    }
                    if (!roomLimitReported && roomTotal > MoneyPolicy.ROOM_MAX - itemTotal) {
                        roomLimitReported = true;
                        issues.add(new SettlementIssue("ROOM_TOTAL_LIMIT", receiptId, null, "제외 품목을 포함한 방 전체 품목 합계가 1억 원을 초과합니다."));
                    }
                    if (!roomLimitReported) {
                        roomTotal += itemTotal;
                    }
                }
                List<Assignment> itemAssignments = assignmentsByItem.getOrDefault(item.getId(), List.of());
                if (item.isExcludedFromSettlement()) {
                    if (!itemAssignments.isEmpty()) {
                        issues.add(new SettlementIssue("INVALID_ASSIGNEES", receiptId, item.getId(), "정산 제외 품목의 참여자 배정을 해제해 주세요."));
                    }
                    continue;
                }
                included = true;
                if (itemAssignments.isEmpty()) {
                    issues.add(new SettlementIssue("MISSING_ASSIGNEES", receiptId, item.getId(), "정산 대상 품목의 참여자를 지정해 주세요."));
                } else if (itemAssignments.stream().anyMatch(a -> !memberIds.contains(a.getRoomMember().getId()))
                    || itemAssignments.stream().map(a -> a.getRoomMember().getId()).distinct().count() != itemAssignments.size()) {
                    issues.add(new SettlementIssue("INVALID_ASSIGNEES", receiptId, item.getId(), "참여자는 해당 방의 멤버로 중복 없이 지정해 주세요."));
                }
            }
            if (included) {
                if (receipt.getPayer() == null) {
                    issues.add(new SettlementIssue("MISSING_PAYER", receiptId, null, "정산 대상 영수증의 결제자를 지정해 주세요."));
                } else if (!memberIds.contains(receipt.getPayer().getId())) {
                    issues.add(new SettlementIssue("INVALID_PAYER", receiptId, null, "결제자를 해당 방의 멤버로 지정해 주세요."));
                }
            }
            if (validAmounts && !receiptLimitReported && declaredTotal != null
                && declaredTotal >= 1 && declaredTotal <= MoneyPolicy.RECEIPT_MAX && declaredTotal != receiptTotal) {
                issues.add(new SettlementIssue("TOTAL_MISMATCH", receiptId, null,
                    "영수증 총액과 전체 품목 합계가 " + Math.abs(declaredTotal - receiptTotal) + "원 다릅니다. 제외 품목도 합계에 포함됩니다."));
            }
        }
        return List.copyOf(issues);
    }

    public record SettlementResult(Long roomId, String roomName, List<MemberSettlement> members,
        boolean ready, List<SettlementIssue> issues) { }

    public record SettlementIssue(String code, Long receiptId, Long itemId, String message) { }

    public record MemberSettlement(Long memberId, Long userId, String nickname, boolean linked,
        long burden, long paid, long net) { }
}
