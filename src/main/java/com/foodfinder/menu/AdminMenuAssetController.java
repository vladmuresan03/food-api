package com.foodfinder.menu;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Admin endpoints for menu assets: upload a file or register a URL.
 * Listing / GET / PUT are covered by the menu-controller tests in the
 * admin package, since the assets are nested under a menu in the UI.
 */
@RestController
@RequestMapping("/admin/api/menus")
public class AdminMenuAssetController {

    private final MenuAssetStorageService service;

    public AdminMenuAssetController(MenuAssetStorageService service) {
        this.service = service;
    }

    @PostMapping(value = "/{menuKey}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MenuAsset upload(@PathVariable String menuKey, @RequestParam("file") MultipartFile file)
            throws IOException {
        return service.uploadFile(menuKey, file);
    }

    @PostMapping(value = "/{menuKey}/assets/url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MenuAsset registerUrl(@PathVariable String menuKey, @RequestBody UrlAsset body) {
        return service.registerUrl(menuKey, body.sourceUrl(), body.assetType(),
                body.originalFilename(), body.sizeBytes(), body.sha256());
    }

    @DeleteMapping("/{menuKey}/assets/{assetKey}")
    public ResponseEntity<Void> archive(@PathVariable String menuKey, @PathVariable String assetKey) {
        service.archive(assetKey);
        return ResponseEntity.noContent().build();
    }

    public record UrlAsset(
            String sourceUrl,
            String assetType,
            String originalFilename,
            Long sizeBytes,
            String sha256) {
    }
}
