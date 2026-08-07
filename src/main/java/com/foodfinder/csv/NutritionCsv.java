package com.foodfinder.csv;

import com.foodfinder.product.Product;
import com.foodfinder.product.ProductNutrition;
import com.foodfinder.product.ProductNutritionRepository;
import com.foodfinder.product.ProductRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-product nutrition facts. 1:1 with {@link Product} (keyed by
 * {@code product_key}, resolved to {@code product_id}). All seven EU
 * 1169/2011 Anex XIV mandatory fields plus the optional fibre can
 * be updated; missing cells leave the column unchanged.
 *
 * <p>Bulk path: a single CSV file is naturally one nutrition row per
 * product, so the importer upserts each row independently. Restaurants
 * that have not declared nutrition simply have no row in
 * {@code product_nutrition} and the consumer UI shows "?" in the
 * nutrition tab.</p>
 */
@Component
public class NutritionCsv {

    static final String[] HEADERS = {
            "product_key", "basis",
            "energy_kcal", "fat_g", "sat_fat_g", "carbs_g", "sugars_g",
            "protein_g", "salt_g", "fiber_g",
            "source_url", "last_verified_at"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);
    private static final Set<String> ALLOWED_BASIS = Set.of("per_100g", "per_100ml", "per_portion");

    private final ProductRepository products;
    private final ProductNutritionRepository nutritions;

    public NutritionCsv(ProductRepository products, ProductNutritionRepository nutritions) {
        this.products = products;
        this.nutritions = nutritions;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<ProductNutrition> pending = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = CsvSupport.parse(reader, HEADERS)) {
            CsvSupport.validateHeaders(parser, ALLOWED, errors);
            if (CsvSupport.hasFatalHeaderErrors(errors)) {
                return new CsvImportReport(dryRun, 0, 0, 0, 0, errors);
            }

            for (CSVRecord record : parser) {
                total++;
                int row = (int) record.getRecordNumber();
                String productKey = CsvSupport.cell(record, "product_key");
                if (productKey == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.MISSING_REQUIRED,
                            "product_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(productKey)) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.INVALID_SLUG,
                            "product_key must be lowercase slug: " + productKey));
                    continue;
                }
                Product p = products.findByProductKey(productKey).orElse(null);
                if (p == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                            "Unknown product_key: " + productKey));
                    continue;
                }
                String basis = CsvSupport.cell(record, "basis");
                if (basis == null) {
                    basis = "per_100g";
                }
                if (!ALLOWED_BASIS.contains(basis)) {
                    errors.add(CsvRowError.of(row, "basis", CsvErrorCode.INVALID_BASIS,
                            "basis must be one of per_100g, per_100ml, per_portion: '" + basis + "'"));
                    continue;
                }
                BigDecimal energy = parseNonNegativeDecimal(record, "energy_kcal", row, errors);
                BigDecimal fat = parseNonNegativeDecimal(record, "fat_g", row, errors);
                BigDecimal satFat = parseNonNegativeDecimal(record, "sat_fat_g", row, errors);
                BigDecimal carbs = parseNonNegativeDecimal(record, "carbs_g", row, errors);
                BigDecimal sugars = parseNonNegativeDecimal(record, "sugars_g", row, errors);
                BigDecimal protein = parseNonNegativeDecimal(record, "protein_g", row, errors);
                BigDecimal salt = parseNonNegativeDecimal(record, "salt_g", row, errors);
                BigDecimal fiber = parseNonNegativeDecimal(record, "fiber_g", row, errors);
                if (errors.stream().anyMatch(e -> e.row() == row)) {
                    continue;
                }
                String sourceUrl = blankToNull(CsvSupport.cell(record, "source_url"));
                if (sourceUrl != null && sourceUrl.length() > 500) {
                    errors.add(CsvRowError.of(row, "source_url", CsvErrorCode.INVALID_URL,
                            "source_url must be at most 500 characters"));
                    continue;
                }
                Instant lastVerified = null;
                String lastVerifiedRaw = blankToNull(CsvSupport.cell(record, "last_verified_at"));
                if (lastVerifiedRaw != null) {
                    try {
                        lastVerified = LocalDate.parse(lastVerifiedRaw)
                                .atStartOfDay().toInstant(ZoneOffset.UTC);
                    } catch (Exception e) {
                        errors.add(CsvRowError.of(row, "last_verified_at",
                                CsvErrorCode.INVALID_DATE,
                                "last_verified_at must be ISO-8601 date (YYYY-MM-DD): '"
                                        + lastVerifiedRaw + "'"));
                        continue;
                    }
                }
                if (dryRun) {
                    continue;
                }
                ProductNutrition existing = nutritions.findById(p.getId()).orElse(null);
                ProductNutrition n = existing == null ? new ProductNutrition() : existing;
                n.setProductId(p.getId());
                n.setBasis(basis);
                n.setEnergyKcal(energy);
                n.setFatG(fat);
                n.setSatFatG(satFat);
                n.setCarbsG(carbs);
                n.setSugarsG(sugars);
                n.setProteinG(protein);
                n.setSaltG(salt);
                n.setFiberG(fiber);
                n.setSourceUrl(sourceUrl);
                n.setLastVerifiedAt(lastVerified);
                pending.add(n);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }
        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (ProductNutrition n : pending) {
                boolean wasNew = (nutritions.findById(n.getProductId()).orElse(null) == null);
                nutritions.save(n);
                if (wasNew) inserted++;
                else updated++;
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
            List<ProductNutrition> rows = nutritions.findAll();
            rows.sort((a, b) -> a.getProductId().compareTo(b.getProductId()));
            for (ProductNutrition n : rows) {
                Product p = products.findById(n.getProductId()).orElse(null);
                String productKey = p == null ? "" : p.getProductKey();
                printer.printRecord(
                        productKey,
                        n.getBasis(),
                        n.getEnergyKcal(),
                        n.getFatG(),
                        n.getSatFatG(),
                        n.getCarbsG(),
                        n.getSugarsG(),
                        n.getProteinG(),
                        n.getSaltG(),
                        n.getFiberG(),
                        n.getSourceUrl(),
                        n.getLastVerifiedAt() == null ? ""
                                : n.getLastVerifiedAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
            }
        }
    }

    private static BigDecimal parseNonNegativeDecimal(CSVRecord record, String field, int row,
                                                      List<CsvRowError> errors) {
        BigDecimal v = CsvSupport.parseDecimal(record, field, errors, row);
        if (v == null) {
            return null;
        }
        if (v.signum() < 0) {
            errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_NUMBER,
                    field + " must not be negative: " + v.toPlainString()));
            return null;
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
