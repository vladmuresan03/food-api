package com.foodfinder.storage;

/**
 * Result of a successful file store: the storage key (relative path) and
 * the size in bytes.
 */
public record StoredFile(String storageKey, long sizeBytes) {
}
