package com.foodfinder.admin;

import com.foodfinder.csv.CsvImportReport;

import java.util.List;

/**
 * Aggregated result of importing a bundle (zip) of CSVs in dependency order.
 * Each entry is the report for one resource. The bundle stops at the first
 * failed resource; subsequent resources have {@code skipped=true} so the
 * caller can see exactly where it broke.
 */
public record BundleImportResult(
        String bundleFilename,
        int totalRows,
        int totalInserted,
        int totalUpdated,
        int totalErrors,
        List<Entry> entries) {

    public record Entry(
            String slug,
            String filename,
            CsvImportReport report,
            boolean skipped) {
    }
}
