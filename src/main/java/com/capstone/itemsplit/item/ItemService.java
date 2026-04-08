package com.capstone.itemsplit.item;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.domain.assignment.AssignmentRepository;
import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.item.ItemRepository;
import com.capstone.itemsplit.domain.receipt.Receipt;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
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
	private final ReceiptRepository receiptRepository;
	private final AssignmentRepository assignmentRepository;
	private final RoomAuthorizationService roomAuthorizationService;

	@Transactional
	public ItemResult addItem(
		Long roomId,
		Long receiptId,
		Long userId,
		String name,
		int price,
		int quantity
	) {
		roomAuthorizationService.checkMember(roomId, userId);
		Receipt receipt = findReceiptInRoom(roomId, receiptId);
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
		int price,
		int quantity
	) {
		roomAuthorizationService.checkMember(roomId, userId);
		Item item = findItemInReceipt(roomId, receiptId, itemId);
		item.update(name.trim(), price, quantity);
		return ItemResult.from(item);
	}

	@Transactional
	public void deleteItem(Long roomId, Long receiptId, Long itemId, Long userId) {
		roomAuthorizationService.checkMember(roomId, userId);
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

	public record ItemResult(Long itemId, Long receiptId, String name, int price, int quantity) {

		private static ItemResult from(Item item) {
			return new ItemResult(
				item.getId(),
				item.getReceipt().getId(),
				item.getName(),
				item.getPrice(),
				item.getQuantity()
			);
		}

	}

}
