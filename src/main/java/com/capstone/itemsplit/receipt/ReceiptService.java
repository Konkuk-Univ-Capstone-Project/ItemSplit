package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.MoneyPolicy;
import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.assignment.Assignment;
import com.capstone.itemsplit.assignment.AssignmentRepository;
import com.capstone.itemsplit.item.Item;
import com.capstone.itemsplit.item.ItemRepository;
import com.capstone.itemsplit.room.*;
import com.capstone.itemsplit.storage.StorageService;
import com.capstone.itemsplit.storage.StorageCleanupService;
import com.capstone.itemsplit.storage.PartialStorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
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
    private final RoomMemberRepository roomMemberRepository;
    private final RoomAuthorizationService roomAuthorizationService;
    private final ReceiptAmountValidator amountValidator;
    private final StorageService storageService;
    private final StorageCleanupService storageCleanupService;
    private final ObjectMapper objectMapper;

	@Transactional
	public UploadReceiptImageResult uploadReceiptImage(
		Long roomId,
		Long userId,
		MultipartFile file,
		String name
	) {
		Room room = roomAuthorizationService.checkMemberForUpdate(roomId, userId);

		validateImageFile(file);

		try {
			StorageService.StoredFile storedFile = storageService.store("receipts/" + roomId, file);
            storageCleanupService.deleteAfterRollback(storedFile.storedPath());
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
        } catch (PartialStorageException exception) {
            storageCleanupService.deleteAfterRollback(exception.storedPath());
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to store the receipt image.");
		} catch (IOException exception) {
			throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to store the receipt image.");
		}
	}


    @Transactional
    public ReceiptDetailResult createManualReceipt(Long roomId, Long userId, String requestId,
        String name, Long payerMemberId, Long declaredTotal, LocalDate purchasedAt, List<SaveItemCommand> commands) {
        Room room = roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) throw invalid("생성 요청 식별자가 필요합니다.");
        if (commands.stream().anyMatch(item -> item.itemId() != null)) throw invalid("새 영수증의 품목 ID는 비워 두어야 합니다.");
        String fingerprint = fingerprint(name, payerMemberId, declaredTotal, purchasedAt, commands);
        Optional<Receipt> existing = receiptRepository.findByRoomIdAndRequestId(roomId, requestId);
        if (existing.isPresent()) {
            Receipt receipt = existing.get();
            if (!fingerprint.equals(receipt.getRequestFingerprint())) throw invalid("같은 요청 식별자로 다른 내용을 저장할 수 없습니다.");
            return detail(receipt);
        }
        MoneyPolicy.declaredTotal(declaredTotal);
        RoomMember payer = resolvePayer(roomId, payerMemberId);
        validateCommands(roomId, null, commands);
        Receipt receipt = Receipt.createManual(room, name.trim(), payer, declaredTotal, purchasedAt);
        receipt.identifyCreation(requestId, fingerprint);
        receiptRepository.save(receipt);
        replaceItems(roomId, receipt, commands);
        return detail(receipt);
    }

    public List<ReceiptSummaryResult> getReceipts(Long roomId, Long userId) {
        roomAuthorizationService.checkMember(roomId, userId);
        return receiptRepository.findAllByRoomId(roomId).stream().map(ReceiptSummaryResult::from).toList();
    }

    public ReceiptDetailResult getReceipt(Long roomId, Long receiptId, Long userId) {
        roomAuthorizationService.checkMember(roomId, userId);
        return detail(findReceiptInRoom(roomId, receiptId));
    }

    @Transactional
    public ReceiptDetailResult updateReceipt(Long roomId, Long receiptId, Long userId, String name,
        Long payerMemberId, Long declaredTotal, LocalDate purchasedAt) {
        roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        MoneyPolicy.declaredTotal(declaredTotal);
        Receipt receipt = findReceiptInRoom(roomId, receiptId);
        receipt.update(name.trim(), resolvePayer(roomId, payerMemberId), declaredTotal, purchasedAt);
        return detail(receipt);
    }

    @Transactional
    public ReceiptDetailResult saveContents(Long roomId, Long receiptId, Long userId, String name,
        Long payerMemberId, Long declaredTotal, LocalDate purchasedAt, List<SaveItemCommand> commands) {
        roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        Receipt receipt = findReceiptInRoom(roomId, receiptId);
        MoneyPolicy.declaredTotal(declaredTotal);
        RoomMember payer = resolvePayer(roomId, payerMemberId);
        validateCommands(roomId, receiptId, commands);
        receipt.update(name.trim(), payer, declaredTotal, purchasedAt);
        replaceItems(roomId, receipt, commands);
        return detail(receipt);
    }

    private void validateCommands(Long roomId, Long receiptId, List<SaveItemCommand> commands) {
        if (commands == null || commands.isEmpty()) throw invalid("품목을 하나 이상 입력해 주세요.");
        Set<Long> seenIds = new HashSet<>();
        long total = 0;
        for (SaveItemCommand command : commands) {
            if (command.itemId() != null && (!seenIds.add(command.itemId()) || receiptId == null
                || itemRepository.findByIdAndReceiptId(command.itemId(), receiptId).isEmpty())) {
                throw invalid("품목 ID가 중복되었거나 해당 영수증의 품목이 아닙니다.");
            }
            total = MoneyPolicy.addWithin(total, MoneyPolicy.itemTotal(command.price(), command.quantity()),
                MoneyPolicy.RECEIPT_MAX, "영수증 품목 합계는 10,000,000원을 넘을 수 없습니다.");
            if (command.excludedFromSettlement() && !command.memberIds().isEmpty()) {
                throw invalid("정산 제외 품목에는 참여자를 지정할 수 없습니다.");
            }
            resolveMembers(roomId, command.memberIds());
        }
        amountValidator.validateReplacement(roomId, receiptId, total);
    }

    private List<RoomMember> resolveMembers(Long roomId, List<Long> ids) {
        Set<Long> unique = new TreeSet<>(ids);
        if (unique.isEmpty()) return List.of();
        List<RoomMember> members = roomMemberRepository.findAllByRoomIdAndIdIn(roomId, unique);
        if (members.size() != unique.size()) throw invalid("참여자는 해당 방의 멤버 ID여야 합니다.");
        return members;
    }

    private void replaceItems(Long roomId, Receipt receipt, List<SaveItemCommand> commands) {
        List<Item> existing = itemRepository.findAllByReceiptId(receipt.getId());
        Map<Long,Item> byId = existing.stream().collect(Collectors.toMap(Item::getId, item -> item));
        Set<Long> retained = commands.stream().map(SaveItemCommand::itemId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> oldIds = existing.stream().map(Item::getId).toList();
        if (!oldIds.isEmpty()) {
            assignmentRepository.deleteAllInBatch(assignmentRepository.findAllByItemIdIn(oldIds));
            itemRepository.deleteAllInBatch(existing.stream().filter(item -> !retained.contains(item.getId())).toList());
        }
        for (SaveItemCommand command : commands) {
            Item item = command.itemId() == null
                ? Item.create(receipt, command.name().trim(), command.price(), command.quantity()) : byId.get(command.itemId());
            item.update(command.name().trim(), command.price(), command.quantity());
            item.setExcludedFromSettlement(command.excludedFromSettlement());
            itemRepository.save(item);
            assignmentRepository.saveAll(resolveMembers(roomId, command.memberIds()).stream()
                .map(member -> Assignment.create(item, member)).toList());
        }
        itemRepository.flush();
    }

    @Transactional
    public void deleteReceipt(Long roomId, Long receiptId, Long userId) {
        roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        Receipt receipt = findReceiptInRoom(roomId, receiptId);
        storageCleanupService.deleteAfterCommit(receipt.getStoredPath());
        List<Long> itemIds = itemRepository.findAllByReceiptId(receiptId).stream().map(Item::getId).toList();
        if (!itemIds.isEmpty()) assignmentRepository.deleteAllByItemIdIn(itemIds);
        itemRepository.deleteAllByReceiptId(receiptId);
        receiptRepository.deleteById(receiptId);
    }

    private Receipt findReceiptInRoom(Long roomId, Long receiptId) {
        return receiptRepository.findByIdAndRoomId(receiptId, roomId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
    }

    private RoomMember resolvePayer(Long roomId, Long payerMemberId) {
        if (payerMemberId == null) return null;
        return roomMemberRepository.findByRoomIdAndIdWithUser(roomId, payerMemberId)
            .orElseThrow(() -> invalid("결제자는 해당 방의 멤버 ID여야 합니다."));
    }

    private ReceiptDetailResult detail(Receipt receipt) {
        List<Item> items = itemRepository.findAllByReceiptId(receipt.getId()).stream()
            .sorted(Comparator.comparing(Item::getId)).toList();
        List<Long> ids = items.stream().map(Item::getId).toList();
        Map<Long,List<Long>> memberIds = ids.isEmpty() ? Map.of() : assignmentRepository.findAllByItemIdIn(ids).stream()
            .collect(Collectors.groupingBy(a -> a.getItem().getId(), Collectors.mapping(a -> a.getRoomMember().getId(), Collectors.toList())));
        long total = 0;
        String warning = null;
        try {
            for (Item item : items) total = Math.addExact(total, Math.multiplyExact(item.getPrice(), (long) item.getQuantity()));
            if (receipt.getDeclaredTotal() != null && receipt.getDeclaredTotal() != total) {
                warning = "검토 필요: 입력한 총액(" + receipt.getDeclaredTotal() + ")과 품목 합계(" + total + ")가 다릅니다.";
            }
        } catch (ArithmeticException exception) {
            warning = "검토 필요: 기존 품목 금액이 계산 범위를 초과합니다. 단가·수량을 수정해 주세요.";
        }
        return new ReceiptDetailResult(receipt.getId(), receipt.getRoom().getId(), receipt.getName(), receipt.getSourceType(),
            receipt.getPayer() == null ? null : receipt.getPayer().getId(),
            receipt.getPayer() == null ? null : receipt.getPayer().getDisplayName(), receipt.getDeclaredTotal(),
            receipt.getPurchasedAt(), receipt.getCreatedAt(), items.stream().map(item -> new ReceiptItemResult(item.getId(),
                item.getName(), item.getPrice(), item.getQuantity(), item.isExcludedFromSettlement(),
                memberIds.getOrDefault(item.getId(), List.of()).stream().sorted().toList())).toList(), warning);
    }

    private String fingerprint(String name, Long payerMemberId, Long declaredTotal, LocalDate purchasedAt, List<SaveItemCommand> items) {
        try {
            var normalized = items.stream().map(item -> new SaveItemCommand(item.itemId(), item.name().trim(), item.price(),
                item.quantity(), item.memberIds().stream().distinct().sorted().toList(), item.excludedFromSettlement())).toList();
            byte[] bytes = objectMapper.writeValueAsBytes(Arrays.asList(name.trim(), payerMemberId, declaredTotal, purchasedAt, normalized));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot identify receipt creation", e);
        }
    }

    private ApiException invalid(String message) { return new ApiException(ErrorCode.VALIDATION_ERROR, message); }

    public record SaveItemCommand(Long itemId, String name, long price, int quantity, List<Long> memberIds, boolean excludedFromSettlement) { }
    public record ReceiptDetailResult(Long receiptId, Long roomId, String name, ReceiptSourceType sourceType,
        Long payerMemberId, String payerNickname, Long declaredTotal, LocalDate purchasedAt, LocalDateTime createdAt,
        List<ReceiptItemResult> items, String warning) { }
    public record ReceiptItemResult(Long itemId, String name, long price, int quantity, boolean excludedFromSettlement, List<Long> memberIds) { }

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
		Long payerMemberId,
		String payerNickname,
		Long declaredTotal,
		LocalDate purchasedAt,
		LocalDateTime createdAt
	) {

		private static ReceiptSummaryResult from(Receipt receipt) {
			return new ReceiptSummaryResult(
				receipt.getId(),
				receipt.getRoom().getId(),
				receipt.getName(),
				receipt.getSourceType(),
				receipt.getPayer() != null ? receipt.getPayer().getId() : null,
				receipt.getPayer() != null ? receipt.getPayer().getDisplayName() : null,
				receipt.getDeclaredTotal(),
				receipt.getPurchasedAt(),
				receipt.getCreatedAt()
			);
		}

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

}
