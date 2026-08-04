package com.foodfinder.csv;

import com.foodfinder.menu.MenuAsset;
import com.foodfinder.menu.MenuAssetRepository;
import com.foodfinder.menu.MenuAssetType;
import com.foodfinder.menu.MenuRepository;
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
import java.util.regex.Pattern;

@Component
public class MenuAssetCsv {

    static final String[] HEADERS = {
            "asset_key", "menu_key", "asset_type", "original_filename",
            "source_url", "mime_type", "size_bytes", "sha256", "sort_order",
            // export-only
            "storage_key"
    };
    private static final Set<String> ALLOWED = Set.of(HEADERS);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final MenuAssetRepository assets;
    private final MenuRepository menus;

    public MenuAssetCsv(MenuAssetRepository assets, MenuRepository menus) {
        this.assets = assets;
        this.menus = menus;
    }

    @Transactional
    public CsvImportReport parse(Reader reader, boolean dryRun) throws IOException {
        List<CsvRowError> errors = new ArrayList<>();
        List<MenuAsset> pending = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = CsvSupport.parse(reader, HEADERS)) {
            CsvSupport.validateHeaders(parser, ALLOWED, errors);
            if (CsvSupport.hasFatalHeaderErrors(errors)) {
                return new CsvImportReport(dryRun, 0, 0, 0, 0, errors);
            }

            Set<String> seenKeys = new TreeSet<>();
            Map<String, Long> menuIdCache = new HashMap<>();

            for (CSVRecord record : parser) {
                total++;
                int row = (int) record.getRecordNumber();

                String key = CsvSupport.cell(record, "asset_key");
                if (key == null) {
                    errors.add(CsvRowError.of(row, "asset_key", CsvErrorCode.MISSING_REQUIRED,
                            "asset_key is required"));
                    continue;
                }
                if (!CsvSupport.isSlug(key)) {
                    errors.add(CsvRowError.of(row, "asset_key", CsvErrorCode.INVALID_SLUG,
                            "asset_key must be lowercase slug: " + key));
                    continue;
                }
                if (!seenKeys.add(key)) {
                    errors.add(CsvRowError.of(row, "asset_key", CsvErrorCode.DUPLICATE_KEY_IN_FILE,
                            "asset_key '" + key + "' appears more than once in this file"));
                    continue;
                }

                String menuKey = CsvSupport.cell(record, "menu_key");
                if (menuKey == null) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.MISSING_REQUIRED,
                            "menu_key is required"));
                    continue;
                }
                Long menuId = menuIdCache.computeIfAbsent(menuKey,
                        k -> menus.findByMenuKey(k).map(m -> m.getId()).orElse(null));
                if (menuId == null) {
                    errors.add(CsvRowError.of(row, "menu_key", CsvErrorCode.UNKNOWN_MENU,
                            "Unknown menu_key: " + menuKey));
                    continue;
                }

                String assetTypeRaw = CsvSupport.cell(record, "asset_type");
                if (assetTypeRaw == null) {
                    errors.add(CsvRowError.of(row, "asset_type", CsvErrorCode.MISSING_REQUIRED,
                            "asset_type is required"));
                    continue;
                }
                MenuAssetType assetType;
                try {
                    assetType = MenuAssetType.valueOf(assetTypeRaw);
                } catch (IllegalArgumentException e) {
                    errors.add(CsvRowError.of(row, "asset_type", CsvErrorCode.INVALID_TYPE,
                            "asset_type must be one of PDF, IMAGE, URL"));
                    continue;
                }

                String sourceUrl = CsvSupport.cell(record, "source_url");
                Long sizeBytes = CsvSupport.parseLong(record, "size_bytes", errors, row);
                if (sizeBytes != null && sizeBytes <= 0) {
                    errors.add(CsvRowError.of(row, "size_bytes", CsvErrorCode.INVALID_VALUE,
                            "size_bytes must be > 0 when present"));
                    continue;
                }
                String sha256 = CsvSupport.cell(record, "sha256");
                if (sha256 != null && !SHA256.matcher(sha256).matches()) {
                    errors.add(CsvRowError.of(row, "sha256", CsvErrorCode.SHA256_FORMAT,
                            "sha256 must be 64 lowercase hex chars"));
                    continue;
                }
                Integer sortOrder = CsvSupport.parseInt(record, "sort_order", errors, row);
                if (sortOrder != null && sortOrder < 0) {
                    errors.add(CsvRowError.of(row, "sort_order", CsvErrorCode.SORT_ORDER_NEGATIVE,
                            "sort_order must be >= 0"));
                    continue;
                }

                if (dryRun) {
                    continue;
                }

                MenuAsset existing = assets.findByAssetKey(key).orElse(null);
                MenuAsset a = existing == null ? new MenuAsset() : existing;
                a.setAssetKey(key);
                a.setMenuId(menuId);
                a.setAssetType(assetType);
                a.setOriginalFilename(CsvSupport.cell(record, "original_filename"));
                a.setSourceUrl(sourceUrl);
                a.setMimeType(CsvSupport.cell(record, "mime_type"));
                a.setSizeBytes(sizeBytes);
                a.setSha256(sha256);
                a.setSortOrder(sortOrder == null ? 0 : sortOrder);
                pending.add(a);
            }
        }

        if (!errors.isEmpty()) {
            return new CsvImportReport(dryRun, total, 0, 0, 0, errors);
        }

        int inserted = 0, updated = 0;
        if (!dryRun) {
            for (MenuAsset a : pending) {
                boolean wasNew = (a.getId() == null);
                assets.save(a);
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
            List<MenuAsset> rows = assets.findAll();
            rows.sort((a, b) -> a.getAssetKey().compareTo(b.getAssetKey()));
            for (MenuAsset a : rows) {
                String menuKey = menus.findById(a.getMenuId()).map(m -> m.getMenuKey()).orElse("");
                printer.printRecord(
                        a.getAssetKey(),
                        menuKey,
                        a.getAssetType() == null ? "" : a.getAssetType().name(),
                        a.getOriginalFilename(),
                        a.getSourceUrl(),
                        a.getMimeType(),
                        a.getSizeBytes() == null ? "" : a.getSizeBytes(),
                        a.getSha256(),
                        a.getSortOrder(),
                        a.getStorageKey());
            }
        }
    }
}
