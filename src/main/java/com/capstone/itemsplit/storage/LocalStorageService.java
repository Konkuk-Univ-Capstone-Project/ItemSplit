package com.capstone.itemsplit.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalStorageService implements StorageService {

	private final Path rootPath;

	public LocalStorageService(@Value("${app.storage.local.root-path:./storage}") String rootPath) {
		this.rootPath = Path.of(rootPath).normalize().toAbsolutePath();
	}

	@Override
	public StoredFile store(String directory, MultipartFile file) throws IOException {
		Path targetDirectory = rootPath.resolve(directory).normalize();
		if (!targetDirectory.startsWith(rootPath)) {
			throw new IOException("Invalid storage directory.");
		}

		Files.createDirectories(targetDirectory);

		String originalFilename = resolveOriginalFilename(file.getOriginalFilename());
		String storedFilename = generateStoredFilename(originalFilename);
		Path targetFile = targetDirectory.resolve(storedFilename).normalize();

		if (!targetFile.startsWith(rootPath)) {
			throw new IOException("Invalid storage path.");
		}

		try (InputStream inputStream = file.getInputStream()) {
			Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
		}

		Path relativePath = rootPath.relativize(targetFile);
		String storedPath = relativePath.toString().replace(File.separatorChar, '/');
		String contentType = StringUtils.hasText(file.getContentType())
			? file.getContentType()
			: "application/octet-stream";

		return new StoredFile(storedPath, originalFilename, contentType, file.getSize());
	}

	private String resolveOriginalFilename(String originalFilename) {
		String cleanedFilename = StringUtils.hasText(originalFilename)
			? StringUtils.cleanPath(originalFilename)
			: "upload";
		Path filenamePath = Path.of(cleanedFilename).getFileName();

		if (filenamePath == null || !StringUtils.hasText(filenamePath.toString())) {
			return "upload";
		}

		return filenamePath.toString();
	}

	private String generateStoredFilename(String originalFilename) {
		String extension = StringUtils.getFilenameExtension(originalFilename);
		String uuid = UUID.randomUUID().toString().replace("-", "");

		if (!StringUtils.hasText(extension)) {
			return uuid;
		}

		return uuid + "." + extension.toLowerCase(Locale.ROOT);
	}

}
