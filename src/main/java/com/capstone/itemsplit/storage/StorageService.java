package com.capstone.itemsplit.storage;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

	StoredFile store(String directory, MultipartFile file) throws IOException;

	record StoredFile(
		String storedPath,
		String originalFilename,
		String contentType,
		long size
	) {
	}

}
