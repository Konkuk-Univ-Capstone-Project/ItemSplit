package com.capstone.itemsplit.storage;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

	/** @throws PartialStorageException when failed upload content still needs cleanup */
	StoredFile store(String directory, MultipartFile file) throws IOException;

	void delete(String storedPath) throws IOException;

	record StoredFile(
		String storedPath,
		String originalFilename,
		String contentType,
		long size
	) {
	}

}
