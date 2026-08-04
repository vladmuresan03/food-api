package com.foodfinder.csv;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/admin/api/csv")
public class CsvController {

    private final RestaurantCsv restaurants;
    private final MenuCsv menus;
    private final ProductCsv products;
    private final MenuItemCsv menuItems;
    private final PhotoCsv photos;
    private final MenuAssetCsv menuAssets;

    public CsvController(RestaurantCsv restaurants, MenuCsv menus, ProductCsv products,
                         MenuItemCsv menuItems, PhotoCsv photos, MenuAssetCsv menuAssets) {
        this.restaurants = restaurants;
        this.menus = menus;
        this.products = products;
        this.menuItems = menuItems;
        this.photos = photos;
        this.menuAssets = menuAssets;
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
    public CsvImportReport importRestaurants(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, restaurants::parse);
    }

    @PostMapping(value = "/menus", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportReport importMenus(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, menus::parse);
    }

    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportReport importProducts(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, products::parse);
    }

    @PostMapping(value = "/menu-items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportReport importMenuItems(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, menuItems::parse);
    }

    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportReport importPhotos(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, photos::parse);
    }

    @PostMapping(value = "/menu-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportReport importMenuAssets(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        return runImport(file, dryRun, menuAssets::parse);
    }

    // ------------------------------------------------------------------ helpers

    private CsvImportReport runImport(MultipartFile file, boolean dryRun, ImportFn fn) throws IOException {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return fn.parse(reader, dryRun);
        }
    }

    @FunctionalInterface
    private interface ImportFn {
        CsvImportReport parse(Reader reader, boolean dryRun) throws IOException;
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
    private interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }
}
