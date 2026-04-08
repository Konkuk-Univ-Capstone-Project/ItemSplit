package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.domain.item.Item;
import com.capstone.itemsplit.domain.item.ItemRepository;
import com.capstone.itemsplit.domain.receipt.Receipt;
import com.capstone.itemsplit.domain.receipt.ReceiptRepository;
import com.capstone.itemsplit.domain.receipt.ReceiptSourceType;
import com.capstone.itemsplit.domain.room.Room;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import com.capstone.itemsplit.storage.StorageService;
import java.io.IOException;
import java.nio.file.Path;
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
		List<ManualReceiptItemCommand> items
	) {
		Room room = roomAuthorizationService.checkMember(roomId, userId);

		Receipt receipt = receiptRepository.save(Receipt.createManual(room, name.trim()));
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
		List<ManualReceiptItemResult> items
	) {

		private static CreateManualReceiptResult from(Receipt receipt, List<Item> items) {
			return new CreateManualReceiptResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
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
