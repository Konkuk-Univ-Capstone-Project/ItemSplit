package com.capstone.itemsplit.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageCleanupServiceTest {

	@TempDir Path root;

	@Test
	void rollbackRemovesNewUploadAfterTransactionEnds() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		StorageCleanupService cleanup = new StorageCleanupService(storage, root.toString());
		String path = upload(storage);
		transaction().executeWithoutResult(status -> {
			cleanup.deleteAfterRollback(path);
			assertThat(root.resolve(path)).exists();
			status.setRollbackOnly();
		});
		assertThat(root.resolve(path)).doesNotExist();
	}

	@Test
	void commitPreservesNewUpload() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		StorageCleanupService cleanup = new StorageCleanupService(storage, root.toString());
		String path = upload(storage);
		transaction().executeWithoutResult(status -> cleanup.deleteAfterRollback(path));
		assertThat(root.resolve(path)).exists();
	}

	@Test
	void failedDeletionSurvivesServiceRestartAndIsRetried() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		StorageCleanupService cleanup = new StorageCleanupService(storage, root.toString());
		String path = upload(storage);
		Path target = root.resolve(path);
		// A directory occupying the file path causes a real filesystem deletion failure.
		Files.delete(target);
		Files.createDirectory(target);
		transaction().executeWithoutResult(status -> cleanup.deleteAfterCommit(path));
		assertThat(target).exists();
		try (var markers = Files.list(root.resolve(".cleanup-pending"))) {
			assertThat(markers).hasSize(1);
		}
		Files.delete(target);
		Files.write(target, new byte[] {1, 2, 3});
		new StorageCleanupService(storage, root.toString()).retryPendingDeletes();
		assertThat(target).doesNotExist();
		try (var markers = Files.list(root.resolve(".cleanup-pending"))) {
			assertThat(markers).isEmpty();
		}
	}

	@Test
	void uploadWithoutTransactionFailsAndCleansTheNewFile() throws Exception {
		LocalStorageService storage = new LocalStorageService(root.toString());
		StorageCleanupService cleanup = new StorageCleanupService(storage, root.toString());
		String path = upload(storage);
		assertThatThrownBy(() -> cleanup.deleteAfterRollback(path)).isInstanceOf(IllegalStateException.class);
		assertThat(root.resolve(path)).doesNotExist();
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(new DataSourceTransactionManager(
			new DriverManagerDataSource("jdbc:h2:mem:cleanup-hooks", "sa", "")));
	}

	private String upload(LocalStorageService storage) throws Exception {
		return storage.store("receipts/1",
			new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3})).storedPath();
	}
}
