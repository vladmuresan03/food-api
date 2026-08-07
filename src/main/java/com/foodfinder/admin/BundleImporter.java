package com.foodfinder.admin;

import com.foodfinder.csv.CsvImportReport;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a zip of CSVs in dependency order. Each inner import runs in
 * its own transaction (via {@link BundleInnerRunner#runOne}), so a
 * failure in one resource does not poison the rest. Stops at the first
 * failure; remaining resources are marked {@code skipped=true}.
 *
 * <p>Each inner import produces its own {@code csv_import_log} row. The
 * outer zip run also gets a {@code slug='bundle'} row capturing the
 * aggregated outcome.
 */
@Service
public class BundleImporter {

    private static final List<String> ORDER = List.of(
            "restaurants", "menus", "products",
            "nutrition", "ingredients",
            "menu-items", "photos", "menu-assets");

    private final BundleInnerRunner inner;
    private final CsvImportLogRepository importLog;

    public BundleImporter(BundleInnerRunner inner, CsvImportLogRepository importLog) {
        this.inner = inner;
        this.importLog = importLog;
    }

    public BundleImportResult importBundle(InputStream zipStream, String bundleFilename,
                                           boolean dryRun, Authentication auth) throws IOException {
        List<Entry> files = readZipEntries(zipStream);
        String actor = auth == null ? "anonymous" : auth.getName();

        CsvImportLog outerLog = CsvImportLog.start("bundle", bundleFilename, actor, dryRun);

        List<BundleImportResult.Entry> results = new ArrayList<>();
        int totalRows = 0, totalInserted = 0, totalUpdated = 0, totalErrors = 0;
        boolean aborted = false;

        for (String slug : ORDER) {
            byte[] content = findEntry(files, slug);
            if (content == null) {
                continue; // not in the zip
            }
            String innerFilename = slug + ".csv";
            if (aborted) {
                results.add(new BundleImportResult.Entry(slug, innerFilename,
                        new CsvImportReport(dryRun, 0, 0, 0, 0, List.of()), true));
                continue;
            }
            try {
                BundleInnerRunner.InnerResult ir = inner.runOne(slug, content, innerFilename,
                        actor, dryRun);
                CsvImportReport report = ir.report();
                results.add(new BundleImportResult.Entry(slug, innerFilename, report, false));
                totalRows += report.totalRows();
                totalInserted += report.inserted();
                totalUpdated += report.updated();
                totalErrors += report.errors().size();
                if (!report.errors().isEmpty()) {
                    aborted = true;
                }
            } catch (RuntimeException | IOException e) {
                // Inner ran finishFailed already; the bundle-level log
                // captures the aggregated cause. Mark the rest as skipped
                // (this one included, since the parse itself failed).
                results.add(new BundleImportResult.Entry(slug, innerFilename,
                        new CsvImportReport(dryRun, 0, 0, 0, 0, List.of()), true));
                aborted = true;
                // continue to mark remaining resources as skipped below
            }
        }

        // Fill in any resources that were not present in the zip. If the
        // bundle aborted, anything past the failure point counts as
        // skipped (we never got there); if the bundle succeeded, the
        // missing ones are just absent.
        for (String slug : ORDER) {
            boolean already = results.stream().anyMatch(r -> r.slug().equals(slug));
            if (!already) {
                results.add(new BundleImportResult.Entry(slug, "—",
                        new CsvImportReport(dryRun, 0, 0, 0, 0, List.of()), aborted));
            }
        }
        results.sort((a, b) -> ORDER.indexOf(a.slug()) - ORDER.indexOf(b.slug()));

        String outerStatus = aborted
                ? (totalErrors > 0 ? "OK with errors" : "FAILED")
                : "OK";
        if ("OK with errors".equals(outerStatus)) {
            outerLog.finishOk(totalRows, totalInserted, totalUpdated, totalErrors);
        } else if ("FAILED".equals(outerStatus)) {
            outerLog.finishFailed("Bundle aborted at first failure");
        } else {
            outerLog.finishOk(totalRows, totalInserted, totalUpdated, totalErrors);
        }
        importLog.save(outerLog);

        return new BundleImportResult(bundleFilename, totalRows, totalInserted,
                totalUpdated, totalErrors, results);
    }

    private record Entry(String name, byte[] content) {
    }

    private static List<Entry> readZipEntries(InputStream in) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                zip.transferTo(buf);
                out.add(new Entry(e.getName(), buf.toByteArray()));
            }
        }
        return out;
    }

    private static byte[] findEntry(List<Entry> entries, String slug) {
        String wanted = slug + ".csv";
        for (Entry e : entries) {
            String name = e.name();
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (base.equals(wanted) || base.equals(slug)) {
                return e.content();
            }
        }
        return null;
    }
}
