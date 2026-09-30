package com.capstone.itemsplit.receipt;

import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.nio.file.Path;
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
	@Autowired private ReceiptRepository receiptRepository;
	@Autowired private RoomRepository roomRepository;
	@Autowired private RoomMemberRepository roomMemberRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	@Test
	void receiptDeletionRemovesOriginalFileAfterCommit() {
		Fixture fixture = fixture();
		ReceiptService.UploadReceiptImageResult uploaded = upload(fixture);
		Path original = storageRoot.resolve(uploaded.storedPath());
		assertThat(original).exists();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			receiptService.deleteReceipt(fixture.roomId(), uploaded.receiptId(), fixture.ownerId());
			assertThat(original).exists();
		});
		assertThat(receiptRepository.findById(uploaded.receiptId())).isEmpty();
		assertThat(original).doesNotExist();
	}

	@Test
	void receiptDeletionRollbackPreservesRecordAndOriginalFile() {
		Fixture fixture = fixture();
		ReceiptService.UploadReceiptImageResult uploaded = upload(fixture);
		Path original = storageRoot.resolve(uploaded.storedPath());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			receiptService.deleteReceipt(fixture.roomId(), uploaded.receiptId(), fixture.ownerId());
			assertThat(original).exists();
			status.setRollbackOnly();
		});
		assertThat(receiptRepository.findById(uploaded.receiptId())).isPresent();
		assertThat(original).exists();
	}

	@Test
	void uploadRollbackRemovesTheNewFileAndDatabaseRecord() {
		Fixture fixture = fixture();
		ReceiptService.UploadReceiptImageResult uploaded = new TransactionTemplate(transactionManager).execute(status -> {
			ReceiptService.UploadReceiptImageResult result = upload(fixture);
			assertThat(storageRoot.resolve(result.storedPath())).exists();
			assertThat(receiptRepository.findById(result.receiptId())).isPresent();
			status.setRollbackOnly();
			return result;
		});
		assertThat(uploaded).isNotNull();
		assertThat(receiptRepository.findById(uploaded.receiptId())).isEmpty();
		assertThat(storageRoot.resolve(uploaded.storedPath())).doesNotExist();
	}

	private ReceiptService.UploadReceiptImageResult upload(Fixture fixture) {
		return receiptService.uploadReceiptImage(fixture.roomId(), fixture.ownerId(),
			new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3}), "Receipt");
	}

	private Fixture fixture() {
		User owner = userRepository.save(User.create(UUID.randomUUID() + "@example.com", "password", "owner"));
		Room room = roomRepository.save(Room.create("Files", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		return new Fixture(room.getId(), owner.getId());
	}

	private record Fixture(Long roomId, Long ownerId) { }
}
