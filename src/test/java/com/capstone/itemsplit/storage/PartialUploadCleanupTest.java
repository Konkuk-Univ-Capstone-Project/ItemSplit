package com.capstone.itemsplit.storage;

import com.capstone.itemsplit.receipt.ReceiptRepository;
import com.capstone.itemsplit.receipt.ReceiptService;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.room.RoomMember;
import com.capstone.itemsplit.room.RoomMemberRepository;
import com.capstone.itemsplit.room.RoomRepository;
import com.capstone.itemsplit.user.User;
import com.capstone.itemsplit.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:partial-upload-cleanup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PartialUploadCleanupTest {

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
	@Autowired private StorageCleanupService cleanupService;
	@Autowired private TemporarilyFailingDeletionStorage storage;

	@Test
	void interruptedUploadRetainsFailedCleanupForRetry() throws Exception {
		User owner = userRepository.save(User.create("partial@example.com", "password", "owner"));
		Room room = roomRepository.save(Room.create("Files", owner));
		roomMemberRepository.save(RoomMember.create(room, owner));
		storage.failDeletion = true;
		MockMultipartFile interrupted = new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1}) {
			@Override
			public InputStream getInputStream() {
				return new InputStream() {
					private boolean first = true;
					@Override public int read() throws IOException {
						if (first) { first = false; return 1; }
						throw new IOException("Interrupted upload after first byte");
					}
				};
			}
		};

		assertThatThrownBy(() -> receiptService.uploadReceiptImage(room.getId(), owner.getId(), interrupted, "Receipt"))
			.isInstanceOf(com.capstone.itemsplit.common.exception.ApiException.class);
		assertThat(receiptRepository.findAllByRoomId(room.getId())).isEmpty();
		Path receiptDirectory = storageRoot.resolve("receipts/" + room.getId());
		try (var files = Files.list(receiptDirectory)) {
			assertThat(files).hasSize(1);
		}
		try (var markers = Files.list(storageRoot.resolve(".cleanup-pending"))) {
			assertThat(markers).hasSize(1);
		}

		storage.failDeletion = false;
		cleanupService.retryPendingDeletes();
		try (var files = Files.list(receiptDirectory)) {
			assertThat(files).isEmpty();
		}
		try (var markers = Files.list(storageRoot.resolve(".cleanup-pending"))) {
			assertThat(markers).isEmpty();
		}
	}

	@TestConfiguration
	static class StorageFailureConfiguration {
		@Bean
		@Primary
		TemporarilyFailingDeletionStorage failingStorage(@Value("${app.storage.local.root-path}") String rootPath) {
			return new TemporarilyFailingDeletionStorage(rootPath);
		}
	}

	static class TemporarilyFailingDeletionStorage extends LocalStorageService {
		boolean failDeletion;

		TemporarilyFailingDeletionStorage(String rootPath) {
			super(rootPath);
		}

		@Override
		public void delete(String storedPath) throws IOException {
			if (failDeletion) {
				throw new IOException("Temporary storage deletion failure");
			}
			super.delete(storedPath);
		}
	}
}
