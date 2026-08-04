package com.foodfinder.csv;

import java.util.List;

/**
 * Result of one CSV import. When {@code errors} is non-empty the operation
 * is a no-op: nothing was inserted or updated in the database.
 */
public record CsvImportReport(
        boolean dryRun,
        int totalRows,
        int inserted,
        int updated,
        int unchanged,
        List<CsvRowError> errors) {
}
