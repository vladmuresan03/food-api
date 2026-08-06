package com.foodfinder.csv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Shared CSV helpers: charset, BOM stripping, slug check, required-header
 * matching, type parsing. Concrete importers use these and stay short.
 */
public final class CsvSupport {

    public static final String UTF8_BOM = "\uFEFF";
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private CsvSupport() {
    }

    private static CSVFormat csvFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
    }

    public static CSVParser parse(Reader reader, String[] requiredHeaders) throws IOException {
        // The requiredHeaders arg is kept for caller documentation / future use
        // but Commons CSV is told to read the actual header line from the file
        // (so we can validate it strictly).
        return csvFormat().parse(reader);
    }

    public static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    public static String cell(CSVRecord record, String name) {
        if (record.isMapped(name)) {
            return readValue(record, name);
        }
        // If the file has a BOM, Commons CSV records the BOM in the first header
        // name, so `name` won't match `isMapped`. Try the BOM-prefixed variant.
        String bomName = UTF8_BOM + name;
        if (record.isMapped(bomName)) {
            return readValue(record, bomName);
        }
        return null;
    }

    private static String readValue(CSVRecord record, String key) {
        String v;
        try {
            v = record.get(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    public static void validateHeaders(CSVParser parser, Set<String> allowed, List<CsvRowError> errors) {
        Set<String> seen = new TreeSet<>();
        for (String header : parser.getHeaderNames()) {
            String clean = stripBom(header);
            if (clean.isEmpty()) {
                continue;
            }
            if (!allowed.contains(clean)) {
                errors.add(CsvRowError.of(0, clean, CsvErrorCode.UNKNOWN_HEADER,
                        "Unknown column '" + clean + "'. Allowed: " + String.join(",", allowed)));
            } else {
                seen.add(clean);
            }
        }
    }

    public static boolean hasFatalHeaderErrors(List<CsvRowError> errors) {
        return errors.stream().anyMatch(e -> e.code() == CsvErrorCode.UNKNOWN_HEADER);
    }

    public static boolean isSlug(String s) {
        return s != null && SLUG.matcher(s).matches();
    }

    public static Integer parseInt(CSVRecord record, String field, List<CsvRowError> errors, int row) {
        String s = cell(record, field);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_NUMBER,
                    "Not an integer: '" + s + "'"));
            return null;
        }
    }

    public static Long parseLong(CSVRecord record, String field, List<CsvRowError> errors, int row) {
        String s = cell(record, field);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_NUMBER,
                    "Not an integer: '" + s + "'"));
            return null;
        }
    }

    public static java.math.BigDecimal parseDecimal(CSVRecord record, String field,
                                                    List<CsvRowError> errors, int row) {
        String s = cell(record, field);
        if (s == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(s);
        } catch (NumberFormatException e) {
            errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_NUMBER,
                    "Not a decimal: '" + s + "'"));
            return null;
        }
    }

    public static Boolean parseBoolean(CSVRecord record, String field,
                                       List<CsvRowError> errors, int row) {
        String s = cell(record, field);
        if (s == null) {
            return null;
        }
        String norm = s.toLowerCase();
        if (norm.equals("true") || norm.equals("yes") || norm.equals("1") || norm.equals("t")) {
            return Boolean.TRUE;
        }
        if (norm.equals("false") || norm.equals("no") || norm.equals("0") || norm.equals("f")) {
            return Boolean.FALSE;
        }
        errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_BOOLEAN,
                "Not a boolean: '" + s + "'"));
        return null;
    }

    public static LocalDate parseDate(CSVRecord record, String field,
                                      List<CsvRowError> errors, int row) {
        String s = cell(record, field);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            errors.add(CsvRowError.of(row, field, CsvErrorCode.INVALID_DATE,
                    "Not an ISO-8601 date (YYYY-MM-DD): '" + s + "'"));
            return null;
        }
    }
}
