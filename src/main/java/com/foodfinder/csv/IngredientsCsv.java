package com.foodfinder.csv;

import com.foodfinder.product.AllergenCode;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductIngredient;
import com.foodfinder.product.ProductIngredientId;
import com.foodfinder.product.ProductIngredientRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One row per ingredient. The natural key is (product_key, position);
 * the importer keeps a "replacement" mode: for each product_key in
 * the file, the existing list is wiped and replaced with the rows in
 * the file (ordered by position). This is the simplest model that
 * matches how restaurants actually update ingredients (the supplier
 * changes one item, they re-send the whole list).
 *
 * <p>Allowed allergen codes are validated against
 * {@link AllergenCode#ALL_CODES} so a typo in the CSV cannot inject
 * a bogus filter value. The {@code percentage} column is the
 * quantitative declaration of characterizing ingredients (Q&AI) per
 * EU 1169/2011 Anex VIII; {@code origin_country} is ISO 3166-1
 * alpha-2.</p>
 */
@Component
public class IngredientsCsv {

    static final String[] HEADERS = {
            "product_key", "position", "name",
            "is_allergen", "allergen_code", "percentage", "origin_country"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);
    private static final int MAX_INGREDIENTS_PER_PRODUCT = 50;

    private final ProductRepository products;
    private final ProductIngredientRepository ingredients;

    public IngredientsCsv(ProductRepository products, ProductIngredientRepository ingredients) {
        this.products = products;
        this.ingredients = ingredients;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        // pendingRows[product_id] = list of (position, ingredient) in order
        Map<Long, List<ProductIngredient>> pending = new HashMap<>();
        Set<String> seenPairs = new TreeSet<>();
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
                Product p = products.findByProductKey(productKey).orElse(null);
                if (p == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                            "Unknown product_key: " + productKey));
                    continue;
                }

                Integer position = CsvSupport.parseInt(record, "position", errors, row);
                if (position == null) {
                    continue;
                }
                if (position < 1 || position > MAX_INGREDIENTS_PER_PRODUCT) {
                    errors.add(CsvRowError.of(row, "position", CsvErrorCode.INVALID_NUMBER,
                            "position must be between 1 and " + MAX_INGREDIENTS_PER_PRODUCT
                                    + ": " + position));
                    continue;
                }

                String name = CsvSupport.cell(record, "name");
                if (name == null) {
                    errors.add(CsvRowError.of(row, "name", CsvErrorCode.MISSING_REQUIRED,
                            "name is required"));
                    continue;
                }
                if (name.length() > 200) {
                    errors.add(CsvRowError.of(row, "name", CsvErrorCode.INVALID_NAME,
                            "name must be at most 200 characters"));
                    continue;
                }

                String pair = productKey + "/" + position;
                if (!seenPairs.add(pair)) {
                    errors.add(CsvRowError.of(row, "position", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "Duplicate (product_key, position) in file: " + pair));
                    continue;
                }

                String allergenCode = blankToNull(CsvSupport.cell(record, "allergen_code"));
                // is_allergen is optional. If absent, infer from allergen_code:
                //   no code  -> not an allergen (default false)
                //   has code -> is an allergen
                // The "is_allergen=true with no allergen_code" combo is an
                // explicit error (you can't claim a row is an allergen
                // without telling the consumer app which one).
                Boolean isAllergenRaw = CsvSupport.parseBoolean(record, "is_allergen", errors, row);
                if (isAllergenRaw == null) {
                    isAllergenRaw = allergenCode != null;
                }
                final boolean isAllergen = isAllergenRaw;
                if (isAllergen && allergenCode == null) {
                    errors.add(CsvRowError.of(row, "allergen_code", CsvErrorCode.INVALID_ALLERGEN,
                            "is_allergen=true but allergen_code is missing (must be one of "
                                    + AllergenCode.ALL_CODES + ")"));
                    continue;
                }
                if (!isAllergen && allergenCode != null) {
                    errors.add(CsvRowError.of(row, "is_allergen", CsvErrorCode.INVALID_ALLERGEN,
                            "allergen_code is set but is_allergen=false"));
                    continue;
                }
                if (isAllergen && !AllergenCode.ALL_CODES.contains(allergenCode.toLowerCase())) {
                    errors.add(CsvRowError.of(row, "allergen_code", CsvErrorCode.INVALID_ALLERGEN,
                            "allergen_code must be one of " + AllergenCode.ALL_CODES
                                    + " (got '" + allergenCode + "')"));
                    continue;
                }

                BigDecimal percentage = null;
                String percentageRaw = blankToNull(CsvSupport.cell(record, "percentage"));
                if (percentageRaw != null) {
                    try {
                        percentage = new BigDecimal(percentageRaw);
                        if (percentage.signum() < 0 || percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
                            errors.add(CsvRowError.of(row, "percentage",
                                    CsvErrorCode.INVALID_PERCENTAGE,
                                    "percentage must be between 0 and 100: " + percentageRaw));
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        errors.add(CsvRowError.of(row, "percentage",
                                CsvErrorCode.INVALID_PERCENTAGE,
                                "percentage is not a decimal: " + percentageRaw));
                        continue;
                    }
                }

                String originCountry = blankToNull(CsvSupport.cell(record, "origin_country"));
                if (originCountry != null && originCountry.length() != 2) {
                    errors.add(CsvRowError.of(row, "origin_country", CsvErrorCode.INVALID_COUNTRY,
                            "origin_country must be ISO 3166-1 alpha-2 (2 letters): "
                                    + originCountry));
                    continue;
                }

                if (dryRun) {
                    continue;
                }

                ProductIngredient pi = new ProductIngredient();
                pi.setId(new ProductIngredientId(p.getId(), position.shortValue()));
                pi.setName(name);
                pi.setAllergen(isAllergen);
                pi.setAllergenCode(allergenCode == null ? null : allergenCode.toLowerCase());
                pi.setPercentage(percentage);
                pi.setOriginCountry(originCountry);
                pending.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(pi);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (Map.Entry<Long, List<ProductIngredient>> e : pending.entrySet()) {
                Long productId = e.getKey();
                int existing = ingredients.findByIdProductIdOrderByIdPositionAsc(productId).size();
                ingredients.deleteByProductId(productId);
                for (ProductIngredient pi : e.getValue()) {
                    ingredients.save(pi);
                }
                // Count as 1 update per product (list replace), not per row;
                // matches the operator's mental model.
                if (existing > 0) updated++;
                else inserted++;
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
            List<Product> all = products.findAll();
            for (Product p : all) {
                List<ProductIngredient> rows = ingredients
                        .findByIdProductIdOrderByIdPositionAsc(p.getId());
                for (ProductIngredient pi : rows) {
                    printer.printRecord(
                            p.getProductKey(),
                            pi.getId().getPosition(),
                            pi.getName(),
                            pi.isAllergen(),
                            pi.getAllergenCode(),
                            pi.getPercentage(),
                            pi.getOriginCountry());
                }
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
