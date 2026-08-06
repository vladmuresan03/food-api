package com.foodfinder.menu;

import com.foodfinder.storage.FileStorage;
import com.foodfinder.storage.Hashes;
import com.foodfinder.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class MenuAssetStorageService {

    public static final long MAX_ASSET_BYTES = 50L * 1024 * 1024; // 50 MB
    public static final Set<String> ALLOWED_ASSET_MIME = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");

    private final FileStorage storage;
    private final MenuRepository menus;
    private final MenuAssetRepository assets;

    public MenuAssetStorageService(FileStorage storage, MenuRepository menus, MenuAssetRepository assets) {
        this.storage = storage;
        this.menus = menus;
        this.assets = assets;
    }

    @Transactional
    public MenuAsset uploadFile(String menuKey, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (file.getSize() > MAX_ASSET_BYTES) {
            throw new IllegalArgumentException("Asset exceeds 50 MB limit");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_ASSET_MIME.contains(mime)) {
            throw new IllegalArgumentException("Unsupported asset MIME: " + mime
                    + " (allowed: " + ALLOWED_ASSET_MIME + ")");
        }
        var menu = menus.findByMenuKey(menuKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown menu_key: " + menuKey));
        MenuAssetType type = mime.equals("application/pdf")
                ? MenuAssetType.PDF : MenuAssetType.IMAGE;

        StoredFile stored;
        String sha;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store("menu-assets/" + menuKey, file.getOriginalFilename(), in);
        }
        try (InputStream in = storage.open(stored.storageKey())) {
            sha = Hashes.sha256Hex(in);
        }

        String assetKey = "asset-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        MenuAsset a = new MenuAsset();
        a.setAssetKey(assetKey);
        a.setMenuId(menu.getId());
        a.setAssetType(type);
        a.setOriginalFilename(file.getOriginalFilename());
        a.setStorageKey(stored.storageKey());
        a.setMimeType(mime);
        a.setSizeBytes(stored.sizeBytes());
        a.setSha256(sha);
        a.setSortOrder(0);
        assets.save(a);
        return a;
    }

    @Transactional
    public MenuAsset registerUrl(String menuKey, String sourceUrl, String assetTypeRaw,
                                 String originalFilename, Long sizeBytes, String sha256) {
        var menu = menus.findByMenuKey(menuKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown menu_key: " + menuKey));
        MenuAssetType type;
        try {
            type = MenuAssetType.valueOf(assetTypeRaw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("asset_type must be one of PDF, IMAGE, URL");
        }
        MenuAsset a = new MenuAsset();
        a.setAssetKey("asset-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        a.setMenuId(menu.getId());
        a.setAssetType(type);
        a.setOriginalFilename(originalFilename);
        a.setSourceUrl(sourceUrl);
        a.setSizeBytes(sizeBytes);
        a.setSha256(sha256);
        a.setSortOrder(0);
        assets.save(a);
        return a;
    }

    @Transactional
    public void archive(String assetKey) {
        // The schema has no archived status for menu_asset, and the spec
        // doesn't define archive behaviour for assets. The current contract
        // is "archive is not supported for menu_assets": we surface this
        // as a 4xx-style error (UnsupportedOperationException → 501) instead
        // of silently no-op'ing. If archive becomes a real requirement,
        // add an is_archived column or a separate archive table.
        throw new UnsupportedOperationException(
                "archive is not supported for menu_assets (no archived status in schema)");
    }
}
