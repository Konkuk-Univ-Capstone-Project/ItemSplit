package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.common.exception.ApiException;
import com.capstone.itemsplit.common.exception.ErrorCode;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomAuthorizationService;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.storage.PartialStorageException;
import com.capstone.itemsplit.storage.StorageCleanupService;
import com.capstone.itemsplit.storage.StorageService;
import com.capstone.itemsplit.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class ReceiptImageService {

    static final int MAX_IMAGES_PER_RECEIPT = 3;

    private final ReceiptRepository receiptRepository;
    private final ReceiptImageRepository receiptImageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final RoomAuthorizationService roomAuthorizationService;
    private final StorageService storageService;
    private final StorageCleanupService storageCleanupService;
    private final DataSize maxImageSize;

    public ReceiptImageService(ReceiptRepository receiptRepository, ReceiptImageRepository receiptImageRepository,
        RoomMemberRepository roomMemberRepository, UserRepository userRepository,
        RoomAuthorizationService roomAuthorizationService, StorageService storageService,
        StorageCleanupService storageCleanupService,
        @Value("${spring.servlet.multipart.max-file-size}") DataSize maxImageSize) {
        this.receiptRepository = receiptRepository;
        this.receiptImageRepository = receiptImageRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.userRepository = userRepository;
        this.roomAuthorizationService = roomAuthorizationService;
        this.storageService = storageService;
        this.storageCleanupService = storageCleanupService;
        this.maxImageSize = maxImageSize;
    }

    /** Creates an image receipt from its first image. The uploader becomes its creator and default payer. */
    @Transactional
    public UploadReceiptImageResult createReceiptWithImage(Long roomId, Long userId, MultipartFile file, String name) {
        Room room = roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        ReceiptImageFormat format = validate(file);
        StorageService.StoredFile stored = store(roomId, file, format);

        RoomMember payer = roomMemberRepository.findByRoomIdAndUserId(roomId, userId).orElse(null);
        Receipt receipt = receiptRepository.save(Receipt.createImageUpload(room,
            resolveReceiptName(name, stored.originalFilename()), userRepository.getReferenceById(userId), payer));
        ReceiptImage image = receiptImageRepository.save(ReceiptImage.create(receipt, 1, stored));
        return new UploadReceiptImageResult(receipt.getId(), roomId, receipt.getName(), receipt.getSourceType(),
            payer == null ? null : payer.getId(), userId, ReceiptImageResult.from(image, 1));
    }

    @Transactional
    public ReceiptImageResult addImage(Long roomId, Long receiptId, Long userId, MultipartFile file) {
        roomAuthorizationService.checkMemberForUpdate(roomId, userId);
        Receipt receipt = receiptRepository.findByIdAndRoomId(receiptId, roomId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Receipt was not found."));
        if (receipt.getSourceType() != ReceiptSourceType.IMAGE_UPLOAD) {
            throw invalid("수기로 만든 영수증에는 이미지를 추가할 수 없습니다.");
        }
        // Receipts created before the creator was recorded stay editable by any member.
        if (receipt.getCreatedBy() != null && !receipt.getCreatedBy().getId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "영수증을 올린 사람만 이미지를 추가할 수 있습니다.");
        }
        // The room lock above serializes uploads, so concurrent requests cannot both pass this check.
        long count = receiptImageRepository.countByReceiptId(receiptId);
        if (count >= MAX_IMAGES_PER_RECEIPT) {
            throw invalid("영수증 하나에 이미지는 " + MAX_IMAGES_PER_RECEIPT + "장까지 올릴 수 있습니다.");
        }
        ReceiptImageFormat format = validate(file);
        StorageService.StoredFile stored = store(roomId, file, format);

        int sortOrder = receiptImageRepository.findMaxSortOrderByReceiptId(receiptId) + 1;
        ReceiptImage image = receiptImageRepository.save(ReceiptImage.create(receipt, sortOrder, stored));
        return ReceiptImageResult.from(image, (int) count + 1);
    }

    private ReceiptImageFormat validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("영수증 이미지 파일이 필요합니다.");
        }
        if (file.getSize() > maxImageSize.toBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                "이미지는 한 장에 " + maxImageSize.toMegabytes() + "MB까지 올릴 수 있습니다.");
        }
        byte[] head;
        try (InputStream inputStream = file.getInputStream()) {
            head = inputStream.readNBytes(ReceiptImageFormat.SIGNATURE_LENGTH);
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read the receipt image.");
        }
        return ReceiptImageFormat.detect(head)
            .orElseThrow(() -> invalid("JPEG 또는 PNG 이미지만 올릴 수 있습니다."));
    }

    private StorageService.StoredFile store(Long roomId, MultipartFile file, ReceiptImageFormat format) {
        try {
            StorageService.StoredFile stored = storageService.store("receipts/" + roomId, file,
                format.extension(), format.contentType());
            storageCleanupService.deleteAfterRollback(stored.storedPath());
            return stored;
        } catch (PartialStorageException exception) {
            storageCleanupService.deleteAfterRollback(exception.storedPath());
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to store the receipt image.");
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to store the receipt image.");
        }
    }

    private String resolveReceiptName(String name, String originalFilename) {
        if (StringUtils.hasText(name)) {
            return name.trim();
        }
        int extensionIndex = originalFilename.lastIndexOf('.');
        return extensionIndex > 0 ? originalFilename.substring(0, extensionIndex) : originalFilename;
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    public record UploadReceiptImageResult(Long receiptId, Long roomId, String name, ReceiptSourceType sourceType,
        Long payerMemberId, Long createdByUserId, ReceiptImageResult image) { }

    public record ReceiptImageResult(Long imageId, Long receiptId, int order, String originalFilename,
        String contentType, long fileSize) {

        private static ReceiptImageResult from(ReceiptImage image, int order) {
            return new ReceiptImageResult(image.getId(), image.getReceipt().getId(), order,
                image.getOriginalFilename(), image.getContentType(), image.getFileSize());
        }
    }
}
