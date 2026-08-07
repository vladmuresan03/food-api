package com.foodfinder.csv;

import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.product.ProductStatus;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Component
public class ProductCsv {

    static final String[] HEADERS = {
            "product_key", "restaurant_key", "name", "description",
            "weight_text", "weight_grams", "category", "tags", "status"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);

    private final ProductRepository products;
    private final RestaurantRepository restaurants;

    public ProductCsv(ProductRepository products, RestaurantRepository restaurants) {
        this.products = products;
        this.restaurants = restaurants;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<Product> pending = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = CsvSupport.parse(reader, HEADERS)) {
            CsvSupport.validateHeaders(parser, ALLOWED, errors);
            if (CsvSupport.hasFatalHeaderErrors(errors)) {
                return new CsvImportReport(dryRun, 0, 0, 0, 0, errors);
            }

            Set<String> seenKeys = new TreeSet<>();
            for (CSVRecord record : parser) {
                total++;
                int row = (int) record.getRecordNumber();

                String key = CsvSupport.cell(record, "product_key");
                if (key == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.MISSING_REQUIRED,
                            "product_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(key)) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.INVALID_SLUG,
                            "product_key must be lowercase slug: " + key));
                    continue;
                }
                if (!seenKeys.add(key)) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "product_key '" + key + "' appears more than once in this file"));
                    continue;
                }

                String restaurantKey = CsvSupport.cell(record, "restaurant_key");
                if (restaurantKey == null) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.MISSING_REQUIRED,
                            "restaurant_key is required"));
                    continue;
                }
                if (!restaurants.existsByRestaurantKey(restaurantKey)) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.UNKNOWN_RESTAURANT,
                            "Unknown restaurant_key: " + restaurantKey));
                    continue;
                }

                String name = CsvSupport.cell(record, "name");
                if (name == null) {
                    errors.add(CsvRowError.of(row, "name", CsvErrorCode.MISSING_REQUIRED,
                            "name is required"));
                    continue;
                }

                ProductStatus status = ProductStatus.DRAFT;
                String statusRaw = CsvSupport.cell(record, "status");
                if (statusRaw != null) {
                    try {
                        status = ProductStatus.valueOf(statusRaw);
                    } catch (IllegalArgumentException e) {
                        errors.add(CsvRowError.of(row, "status", CsvErrorCode.INVALID_STATUS,
                                "status must be one of DRAFT, ACTIVE, ARCHIVED"));
                        continue;
                    }
                }

                if (dryRun) {
                    continue;
                }

                Long restaurantId = restaurants.findByRestaurantKey(restaurantKey).orElseThrow().getId();
                Product existing = products.findByProductKey(key).orElse(null);
                Product p = existing == null ? new Product() : existing;
                p.setProductKey(key);
                p.setRestaurantId(restaurantId);
                p.setName(name);
                p.setDescription(CsvSupport.cell(record, "description"));
                p.setWeightText(CsvSupport.cell(record, "weight_text"));
                p.setWeightGrams(parsePositiveInt(CsvSupport.cell(record, "weight_grams"), "weight_grams"));
                p.setCategory(blankToNull(CsvSupport.cell(record, "category")));
                p.setTags(blankToNull(CsvSupport.cell(record, "tags")));
                p.setStatus(status);
                pending.add(p);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (Product p : pending) {
                boolean wasNew = (p.getId() == null);
                products.save(p);
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
            List<Product> rows = products.findAll();
            rows.sort((a, b) -> a.getProductKey().compareTo(b.getProductKey()));
            for (Product p : rows) {
                String restaurantKey = restaurants.findById(p.getRestaurantId())
                        .map(r -> r.getRestaurantKey()).orElse("");
                printer.printRecord(
                        p.getProductKey(),
                        restaurantKey,
                        p.getName(),
                        p.getDescription(),
                        p.getWeightText(),
                        p.getWeightGrams(),
                        p.getCategory(),
                        p.getTags(),
                        p.getStatus() == null ? "" : p.getStatus().name());
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Integer parsePositiveInt(String cell, String field) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(cell.trim());
            if (v <= 0) {
                throw new IllegalArgumentException(field + " must be positive: " + cell);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + cell);
        }
    }
}
