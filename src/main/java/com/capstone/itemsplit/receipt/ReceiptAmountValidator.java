package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.MoneyPolicy;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReceiptAmountValidator {
    private final ReceiptRepository receipts;
    private final ItemRepository items;

    // Caller holds the room write lock until its transaction commits.
    public void validateReplacement(Long roomId, Long replacedReceiptId, long newTotal) {
        long roomTotal = MoneyPolicy.addWithin(0, newTotal, MoneyPolicy.RECEIPT_MAX,
            "영수증 품목 합계는 10,000,000원을 넘을 수 없습니다.");
        List<Long> otherIds = receipts.findAllByRoomId(roomId).stream()
            .map(Receipt::getId).filter(id -> !id.equals(replacedReceiptId)).toList();
        if (!otherIds.isEmpty()) {
            for (Item item : items.findAllByReceiptIdIn(otherIds)) {
                long total;
                try { total = Math.multiplyExact(item.getPrice(), (long) item.getQuantity()); }
                catch (ArithmeticException e) { total = Long.MAX_VALUE; }
                roomTotal = MoneyPolicy.addWithin(roomTotal, total, MoneyPolicy.ROOM_MAX,
                    "방 품목 합계는 100,000,000원을 넘을 수 없습니다. 기존 입력도 확인해 주세요.");
            }
        }
    }

    public long total(List<Item> values) {
        long sum = 0;
        for (Item item : values) {
            sum = MoneyPolicy.addWithin(sum, MoneyPolicy.itemTotal(item.getPrice(), item.getQuantity()),
                MoneyPolicy.RECEIPT_MAX, "영수증 품목 합계는 10,000,000원을 넘을 수 없습니다.");
        }
        return sum;
    }
}
