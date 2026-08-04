package com.foodfinder.csv;

/**
 * Thrown by an importer when one or more rows have validation errors.
 * The report inside carries the full list of problems; the underlying
 * transaction is rolled back by Spring's {@code @Transactional} on the
 * importer method.
 */
public class CsvValidationException extends RuntimeException {

    private final CsvImportReport report;

    public CsvValidationException(CsvImportReport report) {
        super("CSV import has " + report.errors().size() + " validation error(s)");
        this.report = report;
    }

    public CsvImportReport getReport() {
        return report;
    }
}
