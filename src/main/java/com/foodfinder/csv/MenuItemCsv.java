package com.foodfinder.csv;

import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
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
import java.util.regex.Pattern;

@Component
public class MenuItemCsv {

    static final String[] HEADERS = {
            "menu_key", "product_key", "section_name", "price",
            "currency", "available", "sort_order",
            // legacy column kept for compatibility with earlier exports;
            // ignored on import (MenuItem has no source_url field):
            "source_url"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final MenuItemRepository items;
    private final MenuRepository menus;
    private final ProductRepository products;

    public MenuItemCsv(MenuItemRepository items, MenuRepository menus, ProductRepository products) {
        this.items = items;
        this.menus = menus;
        this.products = products;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<MenuItem> pending = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = CsvSupport.parse(reader, HEADERS)) {
            CsvSupport.validateHeaders(parser, ALLOWED, errors);
            if (CsvSupport.hasFatalHeaderErrors(errors)) {
                return new CsvImportReport(dryRun, 0, 0, 0, 0, errors);
            }

            Set<String> seenPairs = new TreeSet<>();
            Map<String, Long> menuIdCache = new HashMap<>();
            Map<String, Long> productIdCache = new HashMap<>();
            Map<String, Long> productRestaurantCache = new HashMap<>();

            for (CSVRecord record : parser) {
                total++;
                int row = (int) record.getRecordNumber();

                String menuKey = CsvSupport.cell(record, "menu_key");
                String productKey = CsvSupport.cell(record, "product_key");

                if (menuKey == null) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.MISSING_REQUIRED,
                            "menu_key is required"));
                    continue;
                }
                if (productKey == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.MISSING_REQUIRED,
                            "product_key is required"));
                    continue;
                }

                Long menuId = menuIdCache.computeIfAbsent(menuKey,
                        k -> menus.findByMenuKey(k).map(m -> m.getId()).orElse(null));
                if (menuId == null) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.UNKNOWN_MENU,
                            "Unknown menu_key: " + menuKey));
                    continue;
                }
                Long menuRestaurantId = menus.findByMenuKey(menuKey).orElseThrow().getRestaurantId();

                Long productId = productIdCache.computeIfAbsent(productKey,
                        k -> products.findByProductKey(k).map(p -> p.getId()).orElse(null));
                if (productId == null) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                            "Unknown product_key: " + productKey));
                    continue;
                }
                Long productRestaurantId = productRestaurantCache.computeIfAbsent(productKey,
                        k -> products.findByProductKey(k).orElseThrow().getRestaurantId());

                if (!menuRestaurantId.equals(productRestaurantId)) {
                    errors.add(CsvRowError.of(row, "product_key", CsvErrorCode.UNKNOWN_PRODUCT,
                            "product_key '" + productKey + "' belongs to a different restaurant than menu '"
                                    + menuKey + "'"));
                    continue;
                }

                String pair = menuKey + "/" + productKey;
                if (!seenPairs.add(pair)) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "Duplicate (menu_key, product_key) pair in file: " + pair));
                    continue;
                }

                BigDecimal price = CsvSupport.parseDecimal(record, "price", errors, row);
                if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
                    errors.add(CsvRowError.of(row, "price", CsvErrorCode.PRICE_NEGATIVE,
                            "price must be NULL or >= 0"));
                    continue;
                }

                String currency = CsvSupport.cell(record, "currency");
                if (currency == null) {
                    currency = "RON";
                }
                if (!CURRENCY.matcher(currency).matches()) {
                    errors.add(CsvRowError.of(row, "currency", CsvErrorCode.INVALID_CURRENCY,
                            "currency must be 3 uppercase letters: " + currency));
                    continue;
                }

                Integer sortOrder = CsvSupport.parseInt(record, "sort_order", errors, row);
                if (sortOrder != null && sortOrder < 0) {
                    errors.add(CsvRowError.of(row, "sort_order", CsvErrorCode.SORT_ORDER_NEGATIVE,
                            "sort_order must be >= 0"));
                    continue;
                }

                Boolean available = CsvSupport.parseBoolean(record, "available", errors, row);
                if (available == null) {
                    available = Boolean.TRUE;
                }

                String sectionName = CsvSupport.cell(record, "section_name");
                if (sectionName == null) {
                    sectionName = "Altele";
                }

                if (dryRun) {
                    continue;
                }

                MenuItem existing = items.findByMenuIdAndProductId(menuId, productId).orElse(null);
                MenuItem mi = existing == null ? new MenuItem() : existing;
                mi.setMenuId(menuId);
                mi.setProductId(productId);
                mi.setRestaurantId(menuRestaurantId);
                mi.setSectionName(sectionName);
                mi.setPrice(price);
                mi.setCurrency(currency);
                mi.setAvailable(available);
                mi.setSortOrder(sortOrder == null ? 0 : sortOrder);
                pending.add(mi);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (MenuItem mi : pending) {
                boolean wasNew = (mi.getId() == null);
                items.save(mi);
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
            List<MenuItem> rows = items.findAll();
            rows.sort((a, b) -> {
                int c = a.getMenuId().compareTo(b.getMenuId());
                if (c != 0) return c;
                return Integer.compare(a.getSortOrder(), b.getSortOrder());
            });
            for (MenuItem mi : rows) {
                String menuKey = menus.findById(mi.getMenuId()).map(m -> m.getMenuKey()).orElse("");
                String productKey = products.findById(mi.getProductId()).map(p -> p.getProductKey()).orElse("");
                printer.printRecord(
                        menuKey,
                        productKey,
                        mi.getSectionName(),
                        mi.getPrice() == null ? "" : mi.getPrice().toPlainString(),
                        mi.getCurrency(),
                        mi.isAvailable(),
                        mi.getSortOrder(),
                        "");
            }
        }
    }
}
