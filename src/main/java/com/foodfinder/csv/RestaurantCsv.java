package com.foodfinder.csv;

import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.restaurant.RestaurantStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Component
public class RestaurantCsv {

    static final String[] HEADERS = {
            "restaurant_key", "name", "website_url", "address_line",
            "city", "latitude", "longitude", "status"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);

    private final RestaurantRepository repository;

    public RestaurantCsv(RestaurantRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<Restaurant> pending = new ArrayList<>();
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

                String key = CsvSupport.cell(record, "restaurant_key");
                if (key == null) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.MISSING_REQUIRED,
                            "restaurant_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(key)) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.INVALID_SLUG,
                            "restaurant_key must be lowercase slug: " + key));
                    continue;
                }
                if (!seenKeys.add(key)) {
                    errors.add(CsvRowError.of(row, "restaurant_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "restaurant_key '" + key + "' appears more than once in this file"));
                    continue;
                }

                String name = CsvSupport.cell(record, "name");
                if (name == null) {
                    errors.add(CsvRowError.of(row, "name", CsvErrorCode.MISSING_REQUIRED, "name is required"));
                    continue;
                }

                RestaurantStatus status = RestaurantStatus.DRAFT;
                String statusRaw = CsvSupport.cell(record, "status");
                if (statusRaw != null) {
                    try {
                        status = RestaurantStatus.valueOf(statusRaw);
                    } catch (IllegalArgumentException e) {
                        errors.add(CsvRowError.of(row, "status", CsvErrorCode.INVALID_STATUS,
                                "status must be one of DRAFT, ACTIVE, ARCHIVED"));
                        continue;
                    }
                }

                BigDecimal latitude = CsvSupport.parseDecimal(record, "latitude", errors, row);
                BigDecimal longitude = CsvSupport.parseDecimal(record, "longitude", errors, row);
                if (latitude != null && (latitude.compareTo(new BigDecimal("-90")) < 0
                        || latitude.compareTo(new BigDecimal("90")) > 0)) {
                    errors.add(CsvRowError.of(row, "latitude", CsvErrorCode.INVALID_LATITUDE,
                            "latitude must be between -90 and 90"));
                    continue;
                }
                if (longitude != null && (longitude.compareTo(new BigDecimal("-180")) < 0
                        || longitude.compareTo(new BigDecimal("180")) > 0)) {
                    errors.add(CsvRowError.of(row, "longitude", CsvErrorCode.INVALID_LONGITUDE,
                            "longitude must be between -180 and 180"));
                    continue;
                }
                if ((latitude == null) != (longitude == null)) {
                    errors.add(CsvRowError.of(row, "latitude", CsvErrorCode.INVALID_GEO_PAIR,
                            "latitude and longitude must be both null or both set"));
                    continue;
                }

                if (dryRun) {
                    continue;
                }

                Optional<Restaurant> existing = repository.findByRestaurantKey(key);
                Restaurant r = existing.orElseGet(Restaurant::new);
                r.setRestaurantKey(key);
                r.setName(name);
                r.setWebsiteUrl(CsvSupport.cell(record, "website_url"));
                r.setAddressLine(CsvSupport.cell(record, "address_line"));
                String city = CsvSupport.cell(record, "city");
                r.setCity(city != null ? city : "Cluj-Napoca");
                r.setLatitude(latitude);
                r.setLongitude(longitude);
                r.setStatus(status);
                pending.add(r);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (Restaurant r : pending) {
                boolean wasNew = (r.getId() == null);
                repository.save(r);
                if (wasNew) {
                    inserted++;
                } else {
                    updated++;
                }
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
            List<Restaurant> rows = repository.findAll();
            rows.sort((a, b) -> a.getRestaurantKey().compareTo(b.getRestaurantKey()));
            for (Restaurant r : rows) {
                printer.printRecord(
                        r.getRestaurantKey(),
                        r.getName(),
                        r.getWebsiteUrl(),
                        r.getAddressLine(),
                        r.getCity(),
                        r.getLatitude() == null ? "" : r.getLatitude().toPlainString(),
                        r.getLongitude() == null ? "" : r.getLongitude().toPlainString(),
                        r.getStatus() == null ? "" : r.getStatus().name());
            }
        }
    }
}
