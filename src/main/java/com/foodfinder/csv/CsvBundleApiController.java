package com.foodfinder.csv;

import com.foodfinder.admin.BundleImportResult;
import com.foodfinder.admin.BundleImporter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * JSON-friendly bundle import for CI / scripts. Mirror of the
 * form-based {@code POST /admin/csv/bundle} on the view controller.
 * Upload a {@code .zip} containing one CSV per resource, get back a
 * structured JSON report covering every resource in the bundle.
 *
 * <p>The response shape is {@link BundleApiResponse} — one
 * {@code entries[]} element per resource in the canonical order,
 * each carrying its slug, file name, counts, errors, and a
 * {@code skipped} flag for resources past a failure point.
 */
@RestController
@RequestMapping("/admin/api/csv")
public class CsvBundleApiController {

    private final BundleImporter bundleImporter;

    public CsvBundleApiController(BundleImporter bundleImporter) {
        this.bundleImporter = bundleImporter;
    }

    @PostMapping(value = "/bundle",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BundleApiResponse> importBundle(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        BundleImportResult result = bundleImporter.importBundle(
                file.getInputStream(), file.getOriginalFilename(), dryRun, auth);
        return ResponseEntity.ok(BundleApiResponse.from(result));
    }

    /**
     * JSON shape for the bundle endpoint. Top-level totals make CI
     * checks easy: {@code .ok && .totalErrors == 0} means the whole
     * bundle committed without surprises.
     */
    public record BundleApiResponse(
            boolean ok,
            String bundleFilename,
            boolean dryRun,
            int totalRows,
            int totalInserted,
            int totalUpdated,
            int totalErrors,
            java.util.List<BundleEntry> entries) {

        public static BundleApiResponse from(BundleImportResult r) {
            boolean dryRun = r.entries().stream()
                    .findFirst()
                    .map(e -> e.report().dryRun())
                    .orElse(false);
            return new BundleApiResponse(
                    r.totalErrors() == 0,
                    r.bundleFilename(),
                    dryRun,
                    r.totalRows(),
                    r.totalInserted(),
                    r.totalUpdated(),
                    r.totalErrors(),
                    r.entries().stream().map(BundleEntry::from).toList());
        }
    }

    public record BundleEntry(
            String slug,
            String filename,
            int totalRows,
            int inserted,
            int updated,
            int errorCount,
            boolean skipped,
            java.util.List<CsvRowError> errors) {

        public static BundleEntry from(BundleImportResult.Entry e) {
            return new BundleEntry(
                    e.slug(),
                    e.filename(),
                    e.report().totalRows(),
                    e.report().inserted(),
                    e.report().updated(),
                    e.report().errors().size(),
                    e.skipped(),
                    e.report().errors());
        }
    }
}
