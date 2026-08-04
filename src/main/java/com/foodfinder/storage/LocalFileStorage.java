package com.foodfinder.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Stores files on the local filesystem under a configured root directory.
 * Filenames are generated as random, opaque slugs — user-supplied names
 * are kept only as metadata, never written to disk.
 */
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage root: " + this.root, e);
        }
    }

    @Override
    public StoredFile store(String subdir, String originalFilename, InputStream data) throws IOException {
        Path dir = root.resolve(sanitize(subdir)).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("Invalid subdir (path traversal attempt): " + subdir);
        }
        Files.createDirectories(dir);

        String ext = guessExtension(originalFilename);
        String safeName = java.util.UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = dir.resolve(safeName);
        long size = Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);

        // Stored key is the relative path from the root, using '/' as separator.
        String storageKey = root.relativize(target).toString().replace('\\', '/');
        return new StoredFile(storageKey, size);
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        Path p = resolve(storageKey);
        return Files.newInputStream(p);
    }

    @Override
    public long size(String storageKey) throws IOException {
        return Files.size(resolve(storageKey));
    }

    @Override
    public Path resolve(String storageKey) {
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Path traversal attempt: " + storageKey);
        }
        return p;
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "misc";
        }
        // strip path separators, keep alnum, dot, dash, underscore
        String cleaned = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) {
            cleaned = "x" + cleaned;
        }
        return cleaned;
    }

    private static String guessExtension(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return "";
        String ext = originalFilename.substring(dot).toLowerCase();
        // whitelist a few common ones
        return switch (ext) {
            case ".jpg", ".jpeg", ".png", ".webp", ".gif", ".pdf" -> ext;
            default -> "";
        };
    }
}
