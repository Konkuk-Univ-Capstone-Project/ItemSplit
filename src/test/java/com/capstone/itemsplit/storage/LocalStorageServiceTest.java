package com.capstone.itemsplit.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageServiceTest {

	@TempDir Path root;

	@Test
	void storedFileCanBeDeletedAndRepeatedDeletionIsHarmless() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		StorageService.StoredFile stored = storage.store("receipts/1", upload());
		assertThat(Files.readAllBytes(root.resolve(stored.storedPath()))).containsExactly(new byte[] {1, 2, 3});
		storage.delete(stored.storedPath());
		assertThat(root.resolve(stored.storedPath())).doesNotExist();
		storage.delete(stored.storedPath());
	}

	@Test
	void deletionRejectsTraversalAbsolutePathsAndRootDirectory() throws Exception {
		Path storageRoot = Files.createDirectory(root.resolve("storage"));
		Path outside = Files.write(root.resolve("outside.png"), new byte[] {1});
		LocalStorageService storage = new LocalStorageService(storageRoot.toString());
		for (String invalid : new String[] {"../outside.png", outside.toString(), ".", ""}) {
			assertThatThrownBy(() -> storage.delete(invalid)).isInstanceOf(IOException.class);
		}
		assertThat(outside).exists();
		assertThat(storageRoot).isDirectory();
	}

	@Test
	void deletionRejectsSymbolicLinkDirectory() throws Exception {
		Path storageRoot = Files.createDirectory(root.resolve("storage"));
		Path outside = Files.createDirectory(root.resolve("outside"));
		Path outsideFile = Files.write(outside.resolve("receipt.png"), new byte[] {1});
		Files.createSymbolicLink(storageRoot.resolve("receipts"), outside);
		LocalStorageService storage = new LocalStorageService(storageRoot.toString());
		assertThatThrownBy(() -> storage.delete("receipts/receipt.png")).isInstanceOf(IOException.class);
		assertThat(outsideFile).exists();
	}

	@Test
	void storeRejectsParentTraversal() {
		LocalStorageService storage = new LocalStorageService(root.toString());
		assertThatThrownBy(() -> storage.store("../outside", upload())).isInstanceOf(IOException.class);
	}

	@Test
	void storeRejectsSymbolicLinkDirectory() throws Exception {
		Path storageRoot = Files.createDirectory(root.resolve("storage"));
		Path outside = Files.createDirectory(root.resolve("outside"));
		Files.createSymbolicLink(storageRoot.resolve("receipts"), outside);
		LocalStorageService storage = new LocalStorageService(storageRoot.toString());
		assertThatThrownBy(() -> storage.store("receipts", upload())).isInstanceOf(IOException.class);
		try (var files = Files.list(outside)) {
			assertThat(files).isEmpty();
		}
	}

	private MockMultipartFile upload() {
		return new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3});
	}

	@Test
	void interruptedCopyDoesNotLeavePartialFile() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		MockMultipartFile file = new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1}) {
			@Override
			public InputStream getInputStream() {
				return new InputStream() {
					private boolean first = true;
					@Override public int read() throws IOException {
						if (first) { first = false; return 1; }
						throw new IOException("Interrupted upload");
					}
				};
			}
		};
		assertThatThrownBy(() -> storage.store("receipts/1", file)).isInstanceOf(IOException.class);
		try (var files = Files.list(root.resolve("receipts/1"))) {
			assertThat(files).isEmpty();
		}
	}
}
