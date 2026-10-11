package com.capstone.itemsplit.storage;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.util.StringUtils;

/** File naming shared by the storage implementations. */
final class StoredFilenames {

	private StoredFilenames() {
	}

	/** The client's file name without any directory part, kept only as metadata. */
	static String original(String originalFilename) {
		String cleanedFilename = StringUtils.hasText(originalFilename)
			? StringUtils.cleanPath(originalFilename)
			: "upload";
		Path filenamePath = Path.of(cleanedFilename).getFileName();

		if (filenamePath == null || !StringUtils.hasText(filenamePath.toString())) {
			return "upload";
		}

		return filenamePath.toString();
	}

	static String generate(String extension) {
		return UUID.randomUUID().toString().replace("-", "") + "." + extension;
	}

}
