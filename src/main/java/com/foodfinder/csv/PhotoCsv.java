package com.foodfinder.csv;

import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoSourceType;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class PhotoCsv {

    static final String[] HEADERS = {
            "photo_key", "restaurant_key", "product_key", "source_type",
            "external_url", "alt_text", "is_primary", "status",
            // export-only columns; ignored on import:
            "storage_key", "thumbnail_storage_key", "mime_type", "width", "height"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);

    private final PhotoRepository photos;
    private final RestaurantRepository restaurants;
    private final ProductRepository products;

    public PhotoCsv(PhotoRepository photos, RestaurantRepository restaurants, ProductRepository products) {
        this.photos = photos;
        this.restaurants = restaurants;
        this.products = products;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<Photo> pending = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = CsvSupport.parse(reader, HEADERS)) {
            CsvSupport.validateHeaders(parser, ALLOWED, errors);
            if (CsvSupport.hasFatalHeaderErrors(errors)) {
                return new CsvImportReport(dryRun, 0, 0, 0, 0, errors);
            }

            Set<String> seenKeys = new TreeSet<>();
            // track primary-per-scope: "product:<id>" or "restaurant:<id>" -> row
            // of the first primary seen in this file. A second primary for the
            // same scope is a duplicate that the DB would reject with a 500.
            Map<String, Integer> primarySeenAtRow = new HashMap<>();
            Map<String, Long> restaurantIdCache = new HashMap<>();
            Map<String, Long> productIdCache = new HashMap<>();
            Map<String, Long> productRestaurantCache = new HashMap<>();

            for (CSVRecord record : parser) {
                total++;
                int row = (int) record.getRecordNumber();

                String key = CsvSupport.cell(record, "photo_key");
                if (key == null) {
                    errors.add(CsvRowError.of(row, "photo_key", CsvErrorCode.MISSING_REQUIRED,
                            "photo_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(key)) {
                    errors.add(CsvRowError.of(row, "photo_key", CsvErrorCode.INVALID_SLUG,
                            "photo_key must be lowercase slug: " + key));
                    continue;
                }
                if (!seenKeys.add(key)) {
                    errors.add(CsvRowError.of(row, "photo_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "photo_key '" + key + "' appears more than once in this file"));
                    continue;
                }

                String restaurantKey = CsvSupport.cell(record, "restaurant_key");
                if (restaurantKey == null) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.MISSING_REQUIRED,
                            "restaurant_key is required"));
                    continue;
                }
                Long restaurantId = restaurantIdCache.computeIfAbsent(restaurantKey,
                        k -> restaurants.findByRestaurantKey(k).map(r -> r.getId()).orElse(null));
                if (restaurantId == null) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.UNKNOWN_RESTAURANT,
                            "Unknown restaurant_key: " + restaurantKey));
                    continue;
                }

                String productKey = CsvSupport.cell(record, "product_key");
                Long productId = null;
                if (productKey != null) {
                    productId = productIdCache.computeIfAbsent(productKey,
                            k -> products.findByProductKey(k).map(p -> p.getId()).orElse(null));
                    if (productId == null) {
                        errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                                "Unknown product_key: " + productKey));
                        continue;
                    }
                    Long productRestaurantId = productRestaurantCache.computeIfAbsent(productKey,
                            k -> products.findByProductKey(k).orElseThrow().getRestaurantId());
                    if (!productRestaurantId.equals(restaurantId)) {
                        errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                                "product_key '" + productKey + "' belongs to a different restaurant than '"
                                        + restaurantKey + "'"));
                        continue;
                    }
                }

                String sourceTypeRaw = CsvSupport.cell(record, "source_type");
                if (sourceTypeRaw == null) {
                    errors.add(CsvRowError.of(row, "source_type", CsvErrorCode.MISSING_REQUIRED,
                            "source_type is required"));
                    continue;
                }
                PhotoSourceType sourceType;
                try {
                    sourceType = PhotoSourceType.valueOf(sourceTypeRaw);
                } catch (IllegalArgumentException e) {
                    errors.add(CsvRowError.of(row, "source_type", CsvErrorCode.INVALID_TYPE,
                            "source_type must be one of UPLOAD, RESTAURANT_OFFICIAL, GOOGLE_PROTOTYPE, IMPORTED_URL"));
                    continue;
                }

                PhotoStatus status = PhotoStatus.ACTIVE;
                String statusRaw = CsvSupport.cell(record, "status");
                if (statusRaw != null) {
                    try {
                        status = PhotoStatus.valueOf(statusRaw);
                    } catch (IllegalArgumentException e) {
                        errors.add(CsvRowError.of(row, "status", CsvErrorCode.INVALID_STATUS,
                                "status must be one of ACTIVE, ARCHIVED"));
                        continue;
                    }
                }

                String externalUrl = CsvSupport.cell(record, "external_url");
                // B4: export emits "" for missing values; nullify so the DB's
                // ck_photo_storage_xor treats it as unset.
                if (externalUrl != null && externalUrl.isEmpty()) {
                    externalUrl = null;
                }

                Boolean isPrimaryRaw = CsvSupport.parseBoolean(record, "is_primary", errors, row);
                boolean isPrimary = isPrimaryRaw != null && isPrimaryRaw;

                if (isPrimary) {
                    String scope = (productId != null)
                            ? "product:" + productId
                            : "restaurant:" + restaurantId;
                    Integer priorRow = primarySeenAtRow.putIfAbsent(scope, row);
                    if (priorRow != null) {
                        errors.add(CsvRowError.of(row, "is_primary", CsvErrorCode.DUPLICATE_PRIMARY,
                                "is_primary=true already declared on row " + priorRow
                                        + " for the same "
                                        + (productId != null ? "product" : "restaurant")));
                        continue;
                    }
                }

                if (dryRun) {
                    continue;
                }

                Photo existing = photos.findByPhotoKey(key).orElse(null);
                Photo p = existing == null ? new Photo() : existing;
                p.setPhotoKey(key);
                p.setRestaurantId(restaurantId);
                p.setProductId(productId);
                p.setSourceType(sourceType);
                p.setExternalUrl(externalUrl);
                p.setAltText(CsvSupport.cell(record, "alt_text"));
                p.setPrimaryPhoto(isPrimary);
                p.setStatus(status);
                pending.add(p);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        // Demote any other primary photo for the same scope before saving.
        if (!dryRun) {
            for (Photo p : pending) {
                if (!p.isPrimaryPhoto()) {
                    continue;
                }
                if (p.getProductId() != null) {
                    photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getProductId()).ifPresent(other -> {
                        if (!other.getPhotoKey().equals(p.getPhotoKey())) {
                            other.setPrimaryPhoto(false);
                            photos.save(other);
                        }
                    });
                } else {
                    photos.findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(p.getRestaurantId())
                            .ifPresent(other -> {
                                if (!other.getPhotoKey().equals(p.getPhotoKey())) {
                                    other.setPrimaryPhoto(false);
                                    photos.save(other);
                                }
                            });
                }
            }
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (Photo p : pending) {
                boolean wasNew = (p.getId() == null);
                photos.save(p);
                if (wasNew) inserted++; else updated++;
            }
        }
        return new CsvImportReport(dryRun, total, inserted, updated, 0, errors);
    }

    public void write(Writer writer) throws IOException {
        try (CSVPrinter printer = new CSVPrinter(writer,
                org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                        .setHeader(HEADERS)
                        .setRecordSeparator("\n")
                        .build())) {
            List<Photo> rows = photos.findAll();
            rows.sort((a, b) -> a.getPhotoKey().compareTo(b.getPhotoKey()));
            for (Photo p : rows) {
                String restaurantKey = restaurants.findById(p.getRestaurantId())
                        .map(r -> r.getRestaurantKey()).orElse("");
                String productKey = p.getProductId() == null ? ""
                        : products.findById(p.getProductId()).map(pr -> pr.getProductKey()).orElse("");
                printer.printRecord(
                        p.getPhotoKey(),
                        restaurantKey,
                        productKey,
                        p.getSourceType() == null ? "" : p.getSourceType().name(),
                        p.getExternalUrl(),
                        p.getAltText(),
                        p.isPrimaryPhoto(),
                        p.getStatus() == null ? "" : p.getStatus().name(),
                        p.getStorageKey(),
                        p.getThumbnailStorageKey(),
                        p.getMimeType(),
                        p.getWidth() == null ? "" : p.getWidth(),
                        p.getHeight() == null ? "" : p.getHeight());
            }
        }
    }
}
