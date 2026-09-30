package com.capstone.itemsplit.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/** Coordinates local file removal with the database transaction outcome. */
@Service
public class StorageCleanupService {

	private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);
	private final StorageService storageService;
	private final Path pendingDirectory;

	public StorageCleanupService(StorageService storageService,
		@Value("${app.storage.local.root-path:./storage}") String rootPath) {
		this.storageService = storageService;
		this.pendingDirectory = Path.of(rootPath).toAbsolutePath().normalize().resolve(".cleanup-pending");
	}

	public void deleteAfterCommit(String storedPath) {
		if (!StringUtils.hasText(storedPath)) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteWithRetry(storedPath);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deleteWithRetry(storedPath);
			}
		});
	}

	public void deleteAfterRollback(String storedPath) {
		if (!StringUtils.hasText(storedPath)) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteWithRetry(storedPath);
			throw new IllegalStateException("Upload cleanup requires an active transaction.");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					deleteWithRetry(storedPath);
				}
			}
		});
	}

	// The queue survives retryable I/O failures and application restarts. A process crash
	// between DB commit and this callback still requires reconciliation (no DB outbox here).
	private synchronized void deleteWithRetry(String storedPath) {
		Path marker = null;
		try {
			if (Files.isSymbolicLink(pendingDirectory)) {
				throw new IOException("Cleanup queue must not be a symbolic link.");
			}
			Files.createDirectories(pendingDirectory);
			marker = pendingDirectory.resolve(UUID.randomUUID() + ".pending");
			Files.writeString(marker, storedPath, StandardOpenOption.CREATE_NEW);
		} catch (IOException exception) {
			log.error("Could not persist local file cleanup for {}", storedPath, exception);
		}
		try {
			storageService.delete(storedPath);
			if (marker != null) {
				Files.deleteIfExists(marker);
			}
		} catch (IOException | RuntimeException exception) {
			log.error("Local file cleanup failed for {}; pending cleanup will be retried", storedPath, exception);
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedDelayString = "${app.storage.local.cleanup-retry-delay-ms:60000}", initialDelay = 60000)
	public synchronized void retryPendingDeletes() {
		if (!Files.isDirectory(pendingDirectory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var markers = Files.newDirectoryStream(pendingDirectory, "*.pending")) {
			for (Path marker : markers) {
				if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				try {
					storageService.delete(Files.readString(marker));
					Files.deleteIfExists(marker);
				} catch (IOException | RuntimeException exception) {
					log.warn("Local file cleanup retry failed for {}", marker.getFileName(), exception);
				}
			}
		} catch (IOException exception) {
			log.warn("Could not read local file cleanup queue", exception);
		}
	}
}
