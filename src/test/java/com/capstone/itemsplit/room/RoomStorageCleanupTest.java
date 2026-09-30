package com.capstone.itemsplit.room;

import com.capstone.itemsplit.receipt.Receipt;
import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.storage.StorageService;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:room-storage-cleanup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RoomStorageCleanupTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.local.root-path", () -> storageRoot.toString());
	}

	@Autowired private RoomService roomService;
	@Autowired private RoomRepository roomRepository;
	@Autowired private RoomMemberRepository roomMemberRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private ReceiptRepository receiptRepository;
	@Autowired private StorageService storageService;
	@Autowired private PlatformTransactionManager transactionManager;

	@Test
	void roomDeletionRemovesStoredReceiptOnlyAfterCommit() throws Exception {
		Fixture fixture = fixture();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			roomService.deleteRoom(fixture.roomId(), fixture.ownerId());
			assertThat(fixture.file()).exists();
		});
		assertThat(fixture.file()).doesNotExist();
		assertThat(roomRepository.findById(fixture.roomId())).isEmpty();
		assertThat(receiptRepository.findById(fixture.receiptId())).isEmpty();
	}

	@Test
	void rollbackPreservesRoomReceiptAndOriginalFile() throws Exception {
		Fixture fixture = fixture();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			roomService.deleteRoom(fixture.roomId(), fixture.ownerId());
			status.setRollbackOnly();
		});
		assertThat(fixture.file()).exists();
		assertThat(roomRepository.findById(fixture.roomId())).isPresent();
		assertThat(receiptRepository.findById(fixture.receiptId())).isPresent();
	}

	private Fixture fixture() throws Exception {
		User owner = userRepository.save(User.create(UUID.randomUUID() + "@example.com", "password", "owner"));
		Room room = roomRepository.save(Room.create("Files", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		StorageService.StoredFile stored = storageService.store("receipts/" + room.getId(),
			new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3}));
		Receipt receipt = receiptRepository.save(Receipt.createImageUpload(room, "Receipt", stored.storedPath(),
			stored.originalFilename(), stored.contentType(), stored.size()));
		receiptRepository.save(Receipt.createManual(room, "Manual", null, null, null));
		return new Fixture(room.getId(), owner.getId(), receipt.getId(), storageRoot.resolve(stored.storedPath()));
	}

	private record Fixture(Long roomId, Long ownerId, Long receiptId, Path file) { }
}
