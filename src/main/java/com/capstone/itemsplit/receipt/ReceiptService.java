package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.domain.assignment.Assignment;
import com.capstone.itemsplit.domain.assignment.AssignmentRepository;
import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.item.ItemRepository;
import com.capstone.itemsplit.domain.receipt.Receipt;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
import com.capstone.itemsplit.domain.receipt.ReceiptSourceType;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.domain.roommember.RoomMemberRepository;
import com.capstone.itemsplit.domain.user.User;
import com.capstone.itemsplit.domain.user.UserRepository;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import com.capstone.itemsplit.storage.StorageService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptService {

	private final ReceiptRepository receiptRepository;
	private final ItemRepository itemRepository;
	private final AssignmentRepository assignmentRepository;
	private final UserRepository userRepository;
	private final RoomMemberRepository roomMemberRepository;
	private final RoomAuthorizationService roomAuthorizationService;
	private final StorageService storageService;

	@Transactional
	public UploadReceiptImageResult uploadReceiptImage(
		Long roomId,
		Long userId,
		MultipartFile file,
		String name
	) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);

		validateImageFile(file);

		try {
			StorageService.StoredFile storedFile = storageService.store("receipts/" + roomId, file);
			String receiptName = resolveReceiptName(name, storedFile.originalFilename());

			Receipt receipt = receiptRepository.save(Receipt.createImageUpload(
				room,
				receiptName,
				storedFile.storedPath(),
				storedFile.originalFilename(),
				storedFile.contentType(),
				storedFile.size()
			));

			return UploadReceiptImageResult.from(receipt);
		} catch (IOException exception) {
			throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to store the receipt image.");
		}
	}

	@Transactional
	public CreateManualReceiptResult createManualReceipt(
		Long roomId,
		Long userId,
		String name,
		Long payerId,
		Integer declaredTotal,
		List<ManualReceiptItemCommand> items
	) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);
		User payer = resolvePayer(roomId, payerId);

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, name.trim(), payer, declaredTotal));
		List<Item> savedItems = itemRepository.saveAll(
			items.stream()
				.map(item -> Item.create(
					receipt,
					item.name().trim(),
					item.price(),
					item.quantity()
				))
				.toList()
		);

		return CreateManualReceiptResult.from(receipt, savedItems);
	}

	public List<ReceiptSummaryResult> getReceipts(Long roomId, Long userId) {
		roomAuthorizationService.checkMember(roomId, userId);
		return receiptRepository.findAllByRoomId(roomId).stream()
			.map(ReceiptSummaryResult::from)
			.toList();
	}

	public ReceiptDetailResult getReceipt(Long roomId, Long receiptId, Long userId) {
		roomAuthorizationService.checkMember(roomId, userId);
		Receipt receipt = findReceiptInRoom(roomId, receiptId);
		List<Item> items = itemRepository.findAllByReceiptId(receiptId);
		return ReceiptDetailResult.from(receipt, items);
	}

	@Transactional
	public ReceiptDetailResult updateReceipt(
		Long roomId,
		Long receiptId,
		Long userId,
		String name,
		Long payerId,
		Integer declaredTotal
	) {
		roomAuthorizationService.checkMember(roomId, userId);
		Receipt receipt = findReceiptInRoom(roomId, receiptId);
		User payer = resolvePayer(roomId, payerId);
		receipt.update(name.trim(), payer, declaredTotal);
		List<Item> items = itemRepository.findAllByReceiptId(receiptId);
		return ReceiptDetailResult.from(receipt, items);
	}

	@Transactional
	public void deleteReceipt(Long roomId, Long receiptId, Long userId) {
		roomAuthorizationService.checkMember(roomId, userId);
		Receipt receipt = findReceiptInRoom(roomId, receiptId);
		List<Item> items = itemRepository.findAllByReceiptId(receiptId);
		if (!items.isEmpty()) {
			List<Long> itemIds = items.stream().map(Item::getId).toList();
			List<Assignment> assignments = assignmentRepository.findAllByItemIdIn(itemIds);
			if (!assignments.isEmpty()) {
				assignmentRepository.deleteAllInBatch(assignments);
			}
			itemRepository.deleteAllInBatch(items);
		}
		receiptRepository.delete(receipt);
	}

	private Receipt findReceiptInRoom(Long roomId, Long receiptId) {
		Receipt receipt = receiptRepository.findById(receiptId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
		if (!receipt.getRoom().getId().equals(roomId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found in this room.");
		}
		return receipt;
	}

	private User resolvePayer(Long roomId, Long payerId) {
		if (payerId == null) {
			return null;
		}
		if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, payerId)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Payer must be a member of this room.");
		}
		return userRepository.findById(payerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Payer was not found."));
	}

	private void validateImageFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Receipt image file is required.");
		}

		String contentType = file.getContentType();
		if (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "Only image files can be uploaded.");
		}
	}

	private String resolveReceiptName(String name, String originalFilename) {
		if (StringUtils.hasText(name)) {
			return name.trim();
		}

		String filename = Path.of(originalFilename).getFileName().toString();
		int extensionIndex = filename.lastIndexOf('.');
		if (extensionIndex > 0) {
			return filename.substring(0, extensionIndex);
		}

		return filename;
	}

	public record ReceiptSummaryResult(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		Long payerId,
		String payerNickname,
		Integer declaredTotal,
		LocalDateTime createdAt
	) {

		private static ReceiptSummaryResult from(Receipt receipt) {
			return new ReceiptSummaryResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
				receipt.getPayer() != null ? receipt.getPayer().getId() : null,
				receipt.getPayer() != null ? receipt.getPayer().getNickname() : null,
				receipt.getDeclaredTotal(),
				receipt.getCreatedAt()
			);
		}

	}

	public record ReceiptDetailResult(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		Long payerId,
		String payerNickname,
		Integer declaredTotal,
		LocalDateTime createdAt,
		List<ReceiptItemResult> items,
		String warning
	) {

		private static ReceiptDetailResult from(Receipt receipt, List<Item> items) {
			int itemTotal = items.stream().mapToInt(i -> i.getPrice() * i.getQuantity()).sum();
			String warning = null;
			if (receipt.getDeclaredTotal() != null && receipt.getDeclaredTotal() != itemTotal) {
				warning = "검토 필요: 입력한 총액(" + receipt.getDeclaredTotal() + ")과 품목 합계(" + itemTotal + ")가 다릅니다.";
			}
			return new ReceiptDetailResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
				receipt.getPayer() != null ? receipt.getPayer().getId() : null,
				receipt.getPayer() != null ? receipt.getPayer().getNickname() : null,
				receipt.getDeclaredTotal(),
				receipt.getCreatedAt(),
				items.stream()
					.map(item -> new ReceiptItemResult(
						item.getId(),
						item.getName(),
						item.getPrice(),
						item.getQuantity()
					))
					.toList(),
				warning
			);
		}

	}

	public record ReceiptItemResult(Long itemId, String name, int price, int quantity) {
	}

	public record UploadReceiptImageResult(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		String storedPath,
		String originalFilename,
		String contentType,
		Long fileSize
	) {

		private static UploadReceiptImageResult from(Receipt receipt) {
			return new UploadReceiptImageResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
				receipt.getStoredPath(),
				receipt.getOriginalFilename(),
				receipt.getContentType(),
				receipt.getFileSize()
			);
		}

	}

	public record CreateManualReceiptResult(
		Long receiptId,
		Long roomId,
		String name,
		ReceiptSourceType sourceType,
		Long payerId,
		String payerNickname,
		Integer declaredTotal,
		List<ManualReceiptItemResult> items
	) {

		private static CreateManualReceiptResult from(Receipt receipt, List<Item> items) {
			return new CreateManualReceiptResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
				receipt.getPayer() != null ? receipt.getPayer().getId() : null,
				receipt.getPayer() != null ? receipt.getPayer().getNickname() : null,
				receipt.getDeclaredTotal(),
				items.stream()
					.map(item -> new ManualReceiptItemResult(
						item.getId(),
						item.getName(),
						item.getPrice(),
						item.getQuantity()
					))
					.toList()
			);
		}

	}

	public record ManualReceiptItemCommand(String name, int price, int quantity) {
	}

	public record ManualReceiptItemResult(Long itemId, String name, int price, int quantity) {
	}

}
