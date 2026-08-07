package com.foodfinder.csv;

import com.foodfinder.admin.CsvImportLog;
import com.foodfinder.admin.CsvImportLogRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JSON-friendly CSV endpoints: GET for export, POST for import.
 *
 * <p>Both surfaces are gated to {@code ADMIN} (form-login for the UI,
 * basic-auth for scripts). The POST endpoints share the same audit log
 * (see {@code csv_import_log}) as the form-based /admin/csv/{slug} flow.
 *
 * <p>The POST response shape is {@link ImportApiResponse} — a flat
 * record designed for CI consumption. {@code ok=true} means the import
 * ran to completion; consult {@code errorCount} and {@code errors[]} for
 * row-level details.
 */
@RestController
@RequestMapping("/admin/api/csv")
public class CsvController {

    private final RestaurantCsv restaurants;
    private final MenuCsv menus;
    private final ProductCsv products;
    private final NutritionCsv nutrition;
    private final IngredientsCsv ingredients;
    private final MenuItemCsv menuItems;
    private final PhotoCsv photos;
    private final MenuAssetCsv menuAssets;
    private final CsvImportLogRepository importLog;

    public CsvController(RestaurantCsv restaurants, MenuCsv menus, ProductCsv products,
                         NutritionCsv nutrition, IngredientsCsv ingredients,
                         MenuItemCsv menuItems, PhotoCsv photos, MenuAssetCsv menuAssets,
                         CsvImportLogRepository importLog) {
        this.restaurants = restaurants;
        this.menus = menus;
        this.products = products;
        this.nutrition = nutrition;
        this.ingredients = ingredients;
        this.menuItems = menuItems;
        this.photos = photos;
        this.menuAssets = menuAssets;
        this.importLog = importLog;
    }

    // ------------------------------------------------------------------ export

    @GetMapping(value = "/restaurants", produces = "text/csv")
    public ResponseEntity<String> exportRestaurants() throws IOException {
        return csvResponse("restaurants.csv", restaurants::write);
    }

    @GetMapping(value = "/menus", produces = "text/csv")
    public ResponseEntity<String> exportMenus() throws IOException {
        return csvResponse("menus.csv", menus::write);
    }

    @GetMapping(value = "/products", produces = "text/csv")
    public ResponseEntity<String> exportProducts() throws IOException {
        return csvResponse("products.csv", products::write);
    }

    @GetMapping(value = "/nutrition", produces = "text/csv")
    public ResponseEntity<String> exportNutrition() throws IOException {
        return csvResponse("nutrition.csv", nutrition::write);
    }

    @GetMapping(value = "/ingredients", produces = "text/csv")
    public ResponseEntity<String> exportIngredients() throws IOException {
        return csvResponse("ingredients.csv", ingredients::write);
    }

    @GetMapping(value = "/menu-items", produces = "text/csv")
    public ResponseEntity<String> exportMenuItems() throws IOException {
        return csvResponse("menu-items.csv", menuItems::write);
    }

    @GetMapping(value = "/photos", produces = "text/csv")
    public ResponseEntity<String> exportPhotos() throws IOException {
        return csvResponse("photos.csv", photos::write);
    }

    @GetMapping(value = "/menu-assets", produces = "text/csv")
    public ResponseEntity<String> exportMenuAssets() throws IOException {
        return csvResponse("menu-assets.csv", menuAssets::write);
    }

    // ------------------------------------------------------------------ import

    @PostMapping(value = "/restaurants", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importRestaurants(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("restaurants", file, dryRun, auth, restaurants::parse);
    }

    @PostMapping(value = "/menus", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importMenus(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("menus", file, dryRun, auth, menus::parse);
    }

    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importProducts(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("products", file, dryRun, auth, products::parse);
    }

    @PostMapping(value = "/nutrition", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importNutrition(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("nutrition", file, dryRun, auth, nutrition::parse);
    }

    @PostMapping(value = "/ingredients", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importIngredients(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("ingredients", file, dryRun, auth, ingredients::parse);
    }

    @PostMapping(value = "/menu-items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importMenuItems(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("menu-items", file, dryRun, auth, menuItems::parse);
    }

    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importPhotos(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("photos", file, dryRun, auth, photos::parse);
    }

    @PostMapping(value = "/menu-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importMenuAssets(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            Authentication auth) throws IOException {
        return doImport("menu-assets", file, dryRun, auth, menuAssets::parse);
    }

    /**
     * Fallback for unknown slugs. Returns 400 with the standard error
     * shape rather than letting Spring MVC render a 404 page (which is
     * not JSON and confuses CI consumers).
     */
    @PostMapping(value = "/**", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportApiResponse> importUnknown(Authentication auth) {
        return ResponseEntity.status(400).body(ImportApiResponse.error(
                "Unknown CSV resource; valid slugs: restaurants, menus, products, " +
                        "nutrition, ingredients, menu-items, photos, menu-assets"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Run an import and write the audit log in the same transaction.
     * The transaction is REQUIRED (default) so it joins the caller's
     * transaction if one exists, which keeps test isolation working.
     */
    @Transactional
    protected ResponseEntity<ImportApiResponse> doImport(String slug, MultipartFile file,
                                                         boolean dryRun, Authentication auth,
                                                         ImportFn fn) throws IOException {
        String actor = auth == null ? "anonymous" : auth.getName();
        CsvImportLog log = CsvImportLog.start(slug, file.getOriginalFilename(), actor, dryRun);
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            CsvImportReport report = fn.parse(reader, dryRun);
            log.finishOk(report.totalRows(), report.inserted(), report.updated(),
                    report.errors().size());
            importLog.save(log);
            return ResponseEntity.ok(new ImportApiResponse(
                    true, slug, file.getOriginalFilename(), actor, dryRun,
                    report.totalRows(), report.inserted(), report.updated(),
                    report.errors().size(), null, report.errors()));
        } catch (RuntimeException e) {
            log.finishFailed(e.getMessage());
            importLog.save(log);
            return ResponseEntity.status(400).body(ImportApiResponse.error(e.getMessage()));
        }
    }

    private ResponseEntity<String> csvResponse(String filename, ThrowingConsumer<Writer> writer) throws IOException {
        StringWriter sw = new StringWriter();
        writer.accept(sw);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(sw.toString());
    }

    @FunctionalInterface
    private interface ImportFn {
        CsvImportReport parse(Reader reader, boolean dryRun) throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }

    /**
     * Flat response record designed for scripts / CI: one object, all
     * the fields a caller needs in a single check.
     */
    public record ImportApiResponse(
            boolean ok,
            String slug,
            String filename,
            String actor,
            boolean dryRun,
            int totalRows,
            int inserted,
            int updated,
            int errorCount,
            String error,
            List<CsvRowError> errors) {

        public static ImportApiResponse error(String message) {
            return new ImportApiResponse(false, null, null, null, false, 0, 0, 0, 0,
                    message, List.of());
        }
    }
}
