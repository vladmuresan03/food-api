package com.foodfinder.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Tiny storage abstraction. One production implementation today (local disk);
 * tests can use a different one. No abstractions we don't actually need.
 */
public interface FileStorage {

    /**
     * Stores the given input stream under a server-generated safe name within
     * the requested logical subdirectory. Returns the storage key, which is
     * safe to embed in URLs.
     */
    StoredFile store(String subdir, String originalFilename, InputStream data) throws IOException;

    /**
     * Opens a previously stored file for reading.
     */
    InputStream open(String storageKey) throws IOException;

    /**
     * Returns the size in bytes of a stored file.
     */
    long size(String storageKey) throws IOException;

    /**
     * Resolves the absolute on-disk path of a stored file. Used only by the
     * image thumbnailer, which needs a file:// URL. Never expose this in the
     * API.
     */
    Path resolve(String storageKey);
}
