package com.foodfinder.photo;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.storage.FileStorage;
import com.foodfinder.storage.Hashes;
import com.foodfinder.storage.ImageProcessing;
import com.foodfinder.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Photo upload service: validate the upload, store the file, generate a
 * thumbnail, compute SHA-256, persist a {@link Photo} row. Cross-restaurant
 * relations are rejected both here and at the DB level.
 */
@Service
public class PhotoStorageService {

    public static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024; // 20 MB
    public static final Set<String> ALLOWED_PHOTO_MIME = Set.of("image/jpeg", "image/png");

    private final FileStorage storage;
    private final ImageProcessing imageProcessing;
    private final PhotoRepository photos;
    private final RestaurantRepository restaurants;
    private final ProductRepository products;

    public PhotoStorageService(FileStorage storage, ImageProcessing imageProcessing,
                               PhotoRepository photos, RestaurantRepository restaurants,
                               ProductRepository products) {
        this.storage = storage;
        this.imageProcessing = imageProcessing;
        this.photos = photos;
        this.restaurants = restaurants;
        this.products = products;
    }

    @Transactional
    public Photo upload(String restaurantKey, String productKey, String altText,
                        boolean isPrimary, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("Photo exceeds 20 MB limit");
        }
        String mime = detectMime(file);
        if (!ALLOWED_PHOTO_MIME.contains(mime)) {
            throw new IllegalArgumentException("Unsupported image MIME: " + mime
                    + " (allowed: image/jpeg, image/png)");
        }

        var restaurant = restaurants.findByRestaurantKey(restaurantKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey));
        Long restaurantId = restaurant.getId();
        Long productId = null;
        if (productKey != null && !productKey.isBlank()) {
            var product = products.findByProductKey(productKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + productKey));
            if (!product.getRestaurantId().equals(restaurantId)) {
                throw new AdminConflictException("product_key belongs to a different restaurant than restaurant_key");
            }
            productId = product.getId();
        }

        // store original
        StoredFile stored;
        String sha;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store("photos/" + restaurantKey, file.getOriginalFilename(), in);
        }
        try (InputStream in = storage.open(stored.storageKey())) {
            sha = Hashes.sha256Hex(in);
        }

        // decode & generate thumbnail
        Path original = storage.resolve(stored.storageKey());
        ImageProcessing.DecodedImage dims = imageProcessing.decode(original);
        String thumbKey = stored.storageKey().replaceFirst("photos/", "photos/thumbs/");
        // ensure a .jpg extension for thumbnails
        if (!thumbKey.endsWith(".jpg")) {
            thumbKey = thumbKey.replaceFirst("\\.[^.]+$", "") + ".jpg";
        }
        imageProcessing.writeThumbnail(original, storage.resolve(thumbKey));

        String photoKey = "ph-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        // ensure the slug regex matches our key (lowercase alnum + dashes only)
        if (!photoKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalStateException("Generated photo_key is not a slug: " + photoKey);
        }

        if (isPrimary) {
            // demote existing primary photo for the same scope
            if (productId != null) {
                photos.findFirstByProductIdAndPrimaryPhotoTrue(productId).ifPresent(other -> {
                    other.setPrimaryPhoto(false);
                    photos.save(other);
                });
            } else {
                photos.findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(restaurantId).ifPresent(other -> {
                    other.setPrimaryPhoto(false);
                    photos.save(other);
                });
            }
        }

        Photo p = new Photo();
        p.setPhotoKey(photoKey);
        p.setRestaurantId(restaurantId);
        p.setProductId(productId);
        p.setSourceType(PhotoSourceType.UPLOAD);
        p.setStorageKey(stored.storageKey());
        p.setThumbnailStorageKey(thumbKey);
        p.setMimeType(mime);
        p.setWidth(dims.width());
        p.setHeight(dims.height());
        p.setAltText(altText);
        p.setPrimaryPhoto(isPrimary);
        p.setStatus(PhotoStatus.ACTIVE);
        p.setSha256(sha);
        photos.save(p);
        return p;
    }

    @Transactional
    public Photo update(String photoKey, String productKey, String altText, Boolean isPrimary, PhotoStatus status) {
        Photo p = photos.findByPhotoKey(photoKey)
                .orElseThrow(() -> new NoSuchElementException("Photo not found: " + photoKey));
        Long previousProductId = p.getProductId();

        // Resolve the new productId (if productKey was provided) without
        // mutating the entity yet, so we can run the demote against the
        // NEW scope before Hibernate's flush queue gets the move.
        Long newProductId = previousProductId;
        if (productKey != null) {
            if (productKey.isBlank()) {
                newProductId = null;
            } else {
                var product = products.findByProductKey(productKey)
                        .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + productKey));
                if (!product.getRestaurantId().equals(p.getRestaurantId())) {
                    throw new AdminConflictException("product_key belongs to a different restaurant than the photo");
                }
                newProductId = product.getId();
            }
        }
        boolean scopeChanged = !java.util.Objects.equals(previousProductId, newProductId);
        boolean effectiveIsPrimary = (isPrimary == null) ? p.isPrimaryPhoto() : isPrimary;

        // If p is primary and the move puts it into a new scope that
        // already has a primary, demote that one. Run the demote BEFORE
        // mutating p so the flush queue never has two primaries in the
        // same scope simultaneously.
        if (scopeChanged && effectiveIsPrimary) {
            demoteOtherPrimaryInScope(p.getRestaurantId(), newProductId, p.getPhotoKey());
        }

        // Now mutate p. Apply productKey, altText, isPrimary, status.
        if (productKey != null) {
            p.setProductId(newProductId);
        }
        if (altText != null) {
            p.setAltText(altText);
        }
        if (isPrimary != null) {
            if (isPrimary) {
                // still need to demote the existing primary in the
                // current (now) scope if isPrimary is being set to true
                // without a scope change
                if (!scopeChanged) {
                    demoteOtherPrimaryInScope(p.getRestaurantId(), p.getProductId(), p.getPhotoKey());
                }
                p.setPrimaryPhoto(true);
            } else {
                p.setPrimaryPhoto(false);
            }
        }
        if (status != null) {
            p.setStatus(status);
        }
        photos.save(p);
        return p;
    }

    private void demoteOtherPrimaryInScope(Long restaurantId, Long productId, String currentPhotoKey) {
        if (productId != null) {
            photos.findFirstByProductIdAndPrimaryPhotoTrue(productId).ifPresent(other -> {
                if (!other.getPhotoKey().equals(currentPhotoKey)) {
                    other.setPrimaryPhoto(false);
                    photos.save(other);
                }
            });
        } else {
            photos.findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(restaurantId)
                    .ifPresent(other -> {
                        if (!other.getPhotoKey().equals(currentPhotoKey)) {
                            other.setPrimaryPhoto(false);
                            photos.save(other);
                        }
                    });
        }
    }

    private void demoteOtherPrimaryInScope(Photo p) {
        Long productId = p.getProductId();
        if (productId != null) {
            photos.findFirstByProductIdAndPrimaryPhotoTrue(productId).ifPresent(other -> {
                if (!other.getPhotoKey().equals(p.getPhotoKey())) {
                    other.setPrimaryPhoto(false);
                    photos.save(other);
                }
            });
        } else {
            var otherOpt = photos.findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(p.getRestaurantId());
            System.err.println("DEMOTE: restaurant=" + p.getRestaurantId() + " p=" + p.getPhotoKey()
                    + " p.primary=" + p.isPrimaryPhoto() + " p.productId=" + p.getProductId()
                    + " other=" + otherOpt.map(o -> o.getPhotoKey() + "/primary=" + o.isPrimaryPhoto()).orElse("<none>"));
            otherOpt.ifPresent(other -> {
                if (!other.getPhotoKey().equals(p.getPhotoKey())) {
                    other.setPrimaryPhoto(false);
                    photos.save(other);
                    System.err.println("DEMOTE: demoted " + other.getPhotoKey());
                }
            });
        }
    }

    @Transactional
    public void archive(String photoKey) {
        Photo p = photos.findByPhotoKey(photoKey)
                .orElseThrow(() -> new NoSuchElementException("Photo not found: " + photoKey));
        p.setStatus(PhotoStatus.ARCHIVED);
        photos.save(p);
    }

    public Photo requireActive(String photoKey) {
        return photos.findByPhotoKey(photoKey)
                .filter(ph -> ph.getStatus() == PhotoStatus.ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("Photo not available: " + photoKey));
    }

    public List<Photo> all() {
        return photos.findAll();
    }

    private static String detectMime(MultipartFile file) {
        // Trust the declared content type, but verify against the bytes' magic numbers
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(8);
            if (head.length >= 3 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8 && (head[2] & 0xff) == 0xFF) {
                return "image/jpeg";
            }
            if (head.length >= 8
                    && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
        } catch (IOException e) {
            // fall through to the declared content type
        }
        String declared = file.getContentType();
        return declared == null ? "" : declared.toLowerCase();
    }
}
