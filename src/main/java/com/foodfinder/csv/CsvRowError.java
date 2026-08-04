package com.foodfinder.csv;

/**
 * One row-level problem discovered during CSV validation.
 */
public record CsvRowError(
        int row,
        String field,
        CsvErrorCode code,
        String message) {

    public static CsvRowError of(int row, String field, CsvErrorCode code, String message) {
        return new CsvRowError(row, field, code, message);
    }
}
