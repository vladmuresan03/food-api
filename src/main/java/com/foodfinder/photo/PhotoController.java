package com.foodfinder.photo;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.storage.FileStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;

/**
 * Photo serving endpoints.
 *
 * <p>Public: {@code /api/photos/{photoKey}/content} and {@code .../thumbnail}
 * return the binary content of an ACTIVE photo. ARCHIVED photos are
 * intentionally not served.</p>
 *
 * <p>Admin: {@code /admin/api/photos} is the upload + update + archive
 * surface. The admin API is on the same controller for now to keep the URL
 * layout simple.</p>
 */
@RestController
public class PhotoController {

    private final PhotoStorageService service;
    private final FileStorage storage;

    public PhotoController(PhotoStorageService service, FileStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    // ------------------------------------------------------------------ public read

    @GetMapping(value = "/api/photos/{photoKey}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String photoKey) throws IOException {
        Photo p = service.requireActive(photoKey);
        if (p.getStorageKey() == null) {
            throw new NoSuchElementException("Photo has no stored file: " + photoKey);
        }
        InputStream in = storage.open(p.getStorageKey());
        return ResponseEntity.ok()
                .contentType(p.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(p.getMimeType()))
                .contentLength(p.getMimeType() == null ? 0 : (int) storage.size(p.getStorageKey()))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(new InputStreamResource(in));
    }

    @GetMapping(value = "/api/photos/{photoKey}/thumbnail")
    public ResponseEntity<InputStreamResource> thumbnail(@PathVariable String photoKey) throws IOException {
        Photo p = service.requireActive(photoKey);
        String key = p.getThumbnailStorageKey() != null ? p.getThumbnailStorageKey() : p.getStorageKey();
        if (key == null) {
            throw new NoSuchElementException("Photo has no thumbnail: " + photoKey);
        }
        InputStream in = storage.open(key);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(new InputStreamResource(in));
    }

    // ------------------------------------------------------------------ admin

    @PostMapping(value = "/admin/api/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Photo upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("restaurantKey") String restaurantKey,
            @RequestParam(value = "productKey", required = false) String productKey,
            @RequestParam(value = "altText", required = false) String altText,
            @RequestParam(value = "isPrimary", defaultValue = "false") boolean isPrimary,
            @RequestParam(value = "sourceType", required = false) PhotoSourceType sourceType,
            org.springframework.security.core.Authentication auth) throws IOException {
        return service.upload(restaurantKey, productKey, altText, isPrimary, file,
                sourceType == null ? PhotoSourceType.UPLOAD : sourceType, actor(auth));
    }

    @PutMapping("/admin/api/photos/{photoKey}")
    public Photo update(@PathVariable String photoKey,
                        @RequestParam(value = "productKey", required = false) String productKey,
                        @RequestParam(value = "altText", required = false) String altText,
                        @RequestParam(value = "isPrimary", required = false) Boolean isPrimary,
                        @RequestParam(value = "status", required = false) PhotoStatus status,
                        org.springframework.security.core.Authentication auth) {
        return service.update(photoKey, productKey, altText, isPrimary, status, actor(auth));
    }

    @DeleteMapping("/admin/api/photos/{photoKey}")
    public ResponseEntity<Void> delete(@PathVariable String photoKey,
                                       org.springframework.security.core.Authentication auth) {
        service.archive(photoKey, actor(auth));
        return ResponseEntity.noContent().build();
    }

    private static String actor(org.springframework.security.core.Authentication auth) {
        return auth == null ? null : auth.getName();
    }
}
