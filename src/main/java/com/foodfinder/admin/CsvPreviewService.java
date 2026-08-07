package com.foodfinder.admin;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight CSV preview: returns the first N rows plus a flag for
 * whether the file is well-formed. Used by the admin form to show the
 * user what their file looks like before they commit the import.
 *
 * <p>This is intentionally separate from the {@code *Csv} parsers: it
 * does NOT validate types or FK references, only that the file is
 * parseable CSV. The actual import does the real validation.
 */
@Service
public class CsvPreviewService {

    private static final int DEFAULT_PREVIEW_ROWS = 5;

    public record Preview(
            String[] headers,
            List<String[]> rows,
            int totalRows,
            boolean wellFormed,
            String parseError) {

        public static Preview error(String message) {
            return new Preview(new String[0], List.of(), 0, false, message);
        }
    }

    public Preview preview(Reader reader, int maxRows) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setRecordSeparator("\n")
                .build()
                .parse(reader)) {

            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            List<String[]> rows = new ArrayList<>();
            int total = 0;
            for (CSVRecord r : parser) {
                total++;
                if (rows.size() < maxRows) {
                    String[] values = new String[headers.size()];
                    for (int i = 0; i < headers.size(); i++) {
                        values[i] = i < r.size() ? r.get(i) : "";
                    }
                    rows.add(values);
                }
            }
            return new Preview(headers.toArray(new String[0]), rows, total, true, null);
        } catch (IOException | RuntimeException e) {
            return Preview.error(e.getMessage());
        }
    }

    public Preview preview(Reader reader) {
        return preview(reader, DEFAULT_PREVIEW_ROWS);
    }

    public Preview previewFromString(String content) {
        return preview(new StringReader(content == null ? "" : content));
    }

    public Preview previewFromBytes(byte[] bytes) {
        return preview(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
    }
}
