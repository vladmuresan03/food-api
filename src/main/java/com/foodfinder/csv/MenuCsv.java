package com.foodfinder.csv;

import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.menu.MenuType;
import com.foodfinder.restaurant.RestaurantRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Component
public class MenuCsv {

    static final String[] HEADERS = {
            "menu_key", "restaurant_key", "name", "menu_type",
            "status", "source_url", "valid_from", "valid_to"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);

    private final MenuRepository menus;
    private final RestaurantRepository restaurants;

    public MenuCsv(MenuRepository menus, RestaurantRepository restaurants) {
        this.menus = menus;
        this.restaurants = restaurants;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<Menu> pending = new ArrayList<>();
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

                String key = CsvSupport.cell(record, "menu_key");
                if (key == null) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.MISSING_REQUIRED,
                            "menu_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(key)) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.INVALID_SLUG,
                            "menu_key must be lowercase slug: " + key));
                    continue;
                }
                if (!seenKeys.add(key)) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "menu_key '" + key + "' appears more than once in this file"));
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

                MenuType menuType = MenuType.PERMANENT;
                String typeRaw = CsvSupport.cell(record, "menu_type");
                if (typeRaw != null) {
                    try {
                        menuType = MenuType.valueOf(typeRaw);
                    } catch (IllegalArgumentException e) {
                        errors.add(CsvRowError.of(row, "menu_type", CsvErrorCode.INVALID_TYPE,
                                "menu_type must be one of PERMANENT, DAILY, WEEKLY, SEASONAL, OTHER"));
                        continue;
                    }
                }

                MenuStatus status = MenuStatus.DRAFT;
                String statusRaw = CsvSupport.cell(record, "status");
                if (statusRaw != null) {
                    try {
                        status = MenuStatus.valueOf(statusRaw);
                    } catch (IllegalArgumentException e) {
                        errors.add(CsvRowError.of(row, "status", CsvErrorCode.INVALID_STATUS,
                                "status must be one of DRAFT, PUBLISHED, ARCHIVED"));
                        continue;
                    }
                }

                LocalDate validFrom = CsvSupport.parseDate(record, "valid_from", errors, row);
                LocalDate validTo = CsvSupport.parseDate(record, "valid_to", errors, row);
                if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
                    errors.add(CsvRowError.of(row, "valid_to", CsvErrorCode.VALIDITY_RANGE,
                            "valid_to must not be before valid_from"));
                    continue;
                }

                if (dryRun) {
                    continue;
                }

                Long restaurantId = restaurants.findByRestaurantKey(restaurantKey).orElseThrow().getId();
                Menu existing = menus.findByMenuKey(key).orElse(null);
                Menu m = existing == null ? new Menu() : existing;
                m.setMenuKey(key);
                m.setRestaurantId(restaurantId);
                m.setName(name);
                m.setMenuType(menuType);
                m.setStatus(status);
                m.setSourceUrl(CsvSupport.cell(record, "source_url"));
                m.setValidFrom(validFrom);
                m.setValidTo(validTo);
                pending.add(m);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (Menu m : pending) {
                boolean wasNew = (m.getId() == null);
                menus.save(m);
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
            List<Menu> rows = menus.findAll();
            rows.sort((a, b) -> a.getMenuKey().compareTo(b.getMenuKey()));
            for (Menu m : rows) {
                String restaurantKey = restaurants.findById(m.getRestaurantId())
                        .map(r -> r.getRestaurantKey()).orElse("");
                printer.printRecord(
                        m.getMenuKey(),
                        restaurantKey,
                        m.getName(),
                        m.getMenuType() == null ? "" : m.getMenuType().name(),
                        m.getStatus() == null ? "" : m.getStatus().name(),
                        m.getSourceUrl(),
                        m.getValidFrom(),
                        m.getValidTo());
            }
        }
    }
}
