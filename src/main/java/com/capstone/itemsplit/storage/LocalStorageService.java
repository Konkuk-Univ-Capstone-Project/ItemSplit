package com.capstone.itemsplit.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
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
		Path targetDirectory = resolveScopedPath(directory);
		Files.createDirectories(targetDirectory);
		assertNoSymbolicLinks(targetDirectory);

		String originalFilename = resolveOriginalFilename(file.getOriginalFilename());
		String storedFilename = generateStoredFilename(originalFilename);
		Path targetFile = targetDirectory.resolve(storedFilename).normalize();

		if (!targetFile.startsWith(rootPath)) {
			throw new IOException("Invalid storage path.");
		}

		String storedPath = rootPath.relativize(targetFile).toString().replace(File.separatorChar, '/');
		try (InputStream inputStream = file.getInputStream()) {
			Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException exception) {
			try {
				delete(storedPath);
			} catch (IOException | RuntimeException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
				throw new PartialStorageException(storedPath, exception);
			}
			throw exception;
		}

		String contentType = StringUtils.hasText(file.getContentType())
			? file.getContentType()
			: "application/octet-stream";

		return new StoredFile(storedPath, originalFilename, contentType, file.getSize());
	}

	@Override
	public void delete(String storedPath) throws IOException {
		Path target = resolveScopedPath(storedPath);
		if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Storage deletion requires a file path.");
		}
		Files.deleteIfExists(target);
	}

	private Path resolveScopedPath(String value) throws IOException {
		if (!StringUtils.hasText(value)) {
			throw new IOException("Invalid storage path.");
		}
		try {
			Path relative = Path.of(value);
			if (relative.isAbsolute()) {
				throw new IOException("Storage path must be relative.");
			}
			for (Path segment : relative) {
				if (segment.toString().equals("..")) {
					throw new IOException("Invalid storage path.");
				}
			}
			Path target = rootPath.resolve(relative).normalize();
			if (!target.startsWith(rootPath) || target.equals(rootPath)) {
				throw new IOException("Invalid storage path.");
			}
			assertNoSymbolicLinks(target);
			return target;
		} catch (InvalidPathException exception) {
			throw new IOException("Invalid storage path.", exception);
		}
	}

	private void assertNoSymbolicLinks(Path target) throws IOException {
		Path current = rootPath;
		for (Path segment : rootPath.relativize(target)) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symbolic links are not allowed inside storage.");
			}
		}
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
