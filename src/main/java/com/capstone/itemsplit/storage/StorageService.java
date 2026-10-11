package com.capstone.itemsplit.storage;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

	/**
	 * Stores the file under {@code directory} with a server-generated name. The extension and content type
	 * are the ones the caller verified, not the ones the client claimed.
	 *
	 * @throws PartialStorageException when failed upload content still needs cleanup
	 */
	StoredFile store(String directory, MultipartFile file, String extension, String contentType) throws IOException;

	void delete(String storedPath) throws IOException;

	byte[] read(String storedPath) throws IOException;

	record StoredFile(
		String storedPath,
		String originalFilename,
		String contentType,
		long size
	) {
	}

}
