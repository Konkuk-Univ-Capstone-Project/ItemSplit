package com.capstone.itemsplit.item;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptAmountValidator;
import com.capstone.itemsplit.common.MoneyPolicy;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemService {

	private final ItemRepository itemRepository;
	private final ReceiptAmountValidator amountValidator;
	private final ReceiptRepository receiptRepository;
	private final AssignmentRepository assignmentRepository;
	private final RoomAuthorizationService roomAuthorizationService;

	@Transactional
	public ItemResult addItem(
		Long roomId,
		Long receiptId,
		Long userId,
		String name,
		long price,
		int quantity
	) {
		roomAuthorizationService.checkMemberForUpdate(roomId, userId);
		Receipt receipt = findReceiptInRoom(roomId, receiptId);
		long total = MoneyPolicy.addWithin(amountValidator.total(itemRepository.findAllByReceiptId(receiptId)),
            MoneyPolicy.itemTotal(price, quantity), MoneyPolicy.RECEIPT_MAX, "영수증 품목 합계는 10,000,000원을 넘을 수 없습니다.");
        amountValidator.validateReplacement(roomId, receiptId, total);
        Item item = itemRepository.save(Item.create(receipt, name.trim(), price, quantity));
		return ItemResult.from(item);
	}

	@Transactional
	public ItemResult updateItem(
		Long roomId,
		Long receiptId,
		Long itemId,
		Long userId,
		String name,
		long price,
		int quantity
	) {
		roomAuthorizationService.checkMemberForUpdate(roomId, userId);
		Item item = findItemInReceipt(roomId, receiptId, itemId);
		long otherTotal = amountValidator.total(itemRepository.findAllByReceiptId(receiptId).stream()
            .filter(existing -> !existing.getId().equals(itemId)).toList());
        long total = MoneyPolicy.addWithin(otherTotal, MoneyPolicy.itemTotal(price, quantity), MoneyPolicy.RECEIPT_MAX,
            "영수증 품목 합계는 10,000,000원을 넘을 수 없습니다.");
        amountValidator.validateReplacement(roomId, receiptId, total);
        item.update(name.trim(), price, quantity);
		return ItemResult.from(item);
	}

	@Transactional
	public void deleteItem(Long roomId, Long receiptId, Long itemId, Long userId) {
		roomAuthorizationService.checkMemberForUpdate(roomId, userId);
		findItemInReceipt(roomId, receiptId, itemId);
		assignmentRepository.deleteAllByItemIdIn(List.of(itemId));
		itemRepository.deleteById(itemId);
	}

	private Receipt findReceiptInRoom(Long roomId, Long receiptId) {
		Receipt receipt = receiptRepository.findById(receiptId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
		if (!receipt.getRoom().getId().equals(roomId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found in this room.");
		}
		return receipt;
	}

	private Item findItemInReceipt(Long roomId, Long receiptId, Long itemId) {
		findReceiptInRoom(roomId, receiptId);
		Item item = itemRepository.findById(itemId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Item was not found."));
		if (!item.getReceipt().getId().equals(receiptId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Item was not found in this receipt.");
		}
		return item;
	}

	public record ItemResult(Long itemId, Long receiptId, String name, long price, int quantity, boolean excludedFromSettlement) {

		private static ItemResult from(Item item) {
			return new ItemResult(
				item.getId(),
				item.getReceipt().getId(),
				item.getName(),
				item.getPrice(),
				item.getQuantity(),
				item.isExcludedFromSettlement()
			);
		}

	}

}
