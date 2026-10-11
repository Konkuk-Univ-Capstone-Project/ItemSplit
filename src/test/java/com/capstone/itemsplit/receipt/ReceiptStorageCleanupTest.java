package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-storage-cleanup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ReceiptStorageCleanupTest {

	@TempDir static Path storageRoot;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.local.root-path", () -> storageRoot.toString());
	}

	@Autowired private ReceiptService receiptService;
	@Autowired private ReceiptImageService receiptImageService;
	@Autowired private ReceiptImageRepository receiptImageRepository;
	@Autowired private ReceiptRepository receiptRepository;
	@Autowired private RoomRepository roomRepository;
	@Autowired private RoomMemberRepository roomMemberRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	@Test
	void receiptDeletionRemovesOriginalFileAfterCommit() {
		Fixture fixture = fixture();
		ReceiptImageService.UploadReceiptImageResult uploaded = upload(fixture);
		receiptImageService.addImage(fixture.roomId(), uploaded.receiptId(), fixture.ownerId(), image());
		List<Path> originals = storedFiles(uploaded.receiptId());
		assertThat(originals).hasSize(2).allSatisfy(original -> assertThat(original).exists());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			receiptService.deleteReceipt(fixture.roomId(), uploaded.receiptId(), fixture.ownerId());
			assertThat(originals).allSatisfy(original -> assertThat(original).exists());
		});
		assertThat(receiptRepository.findById(uploaded.receiptId())).isEmpty();
		assertThat(receiptImageRepository.countByReceiptId(uploaded.receiptId())).isZero();
		assertThat(originals).allSatisfy(original -> assertThat(original).doesNotExist());
	}

	@Test
	void receiptDeletionRollbackPreservesRecordAndOriginalFile() {
		Fixture fixture = fixture();
		ReceiptImageService.UploadReceiptImageResult uploaded = upload(fixture);
		Path original = storedFiles(uploaded.receiptId()).get(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			receiptService.deleteReceipt(fixture.roomId(), uploaded.receiptId(), fixture.ownerId());
			assertThat(original).exists();
			status.setRollbackOnly();
		});
		assertThat(receiptRepository.findById(uploaded.receiptId())).isPresent();
		assertThat(receiptImageRepository.countByReceiptId(uploaded.receiptId())).isEqualTo(1);
		assertThat(original).exists();
	}

	@Test
	void uploadRollbackRemovesTheNewFileAndDatabaseRecord() {
		Fixture fixture = fixture();
		Path stored = new TransactionTemplate(transactionManager).execute(status -> {
			ReceiptImageService.UploadReceiptImageResult result = upload(fixture);
			Path file = storedFiles(result.receiptId()).get(0);
			assertThat(file).exists();
			assertThat(receiptRepository.findById(result.receiptId())).isPresent();
			status.setRollbackOnly();
			return file;
		});
		assertThat(stored).isNotNull().doesNotExist();
		assertThat(receiptRepository.findAllByRoomId(fixture.roomId())).isEmpty();
	}

	private ReceiptImageService.UploadReceiptImageResult upload(Fixture fixture) {
		return receiptImageService.createReceiptWithImage(fixture.roomId(), fixture.ownerId(), image(), "Receipt");
	}

	private MockMultipartFile image() {
		return new MockMultipartFile("file", "receipt.png", "image/png",
			new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
	}

	private List<Path> storedFiles(Long receiptId) {
		return receiptImageRepository.findAllByReceiptId(receiptId).stream()
			.map(image -> storageRoot.resolve(image.getStoredPath()))
			.toList();
	}

	private Fixture fixture() {
		User owner = userRepository.save(User.create(UUID.randomUUID() + "@example.com", "password", "owner"));
		Room room = roomRepository.save(Room.create("Files", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		return new Fixture(room.getId(), owner.getId());
	}

	private record Fixture(Long roomId, Long ownerId) { }
}
