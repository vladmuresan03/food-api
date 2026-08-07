package com.foodfinder.admin;

import com.foodfinder.csv.CsvImportReport;
import com.foodfinder.csv.IngredientsCsv;
import com.foodfinder.csv.MenuAssetCsv;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.NutritionCsv;
import com.foodfinder.csv.PhotoCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Helper bean for {@link BundleImporter}. Each inner parse is wrapped in
 * a {@code REQUIRES_NEW} transaction so a DB-level failure (e.g. a
 * PostgreSQL constraint violation) does not poison the surrounding
 * bundle transaction: a constraint error in PostgreSQL marks the entire
 * transaction as aborted, which is fatal for any subsequent writes
 * (including the log row).
 *
 * <p>Calling inner parsers directly from within the same Spring proxy
 * would not work for {@code REQUIRES_NEW} (self-invocation bypasses the
 * proxy), hence this separate bean.
 */
@Service
public class BundleInnerRunner {

    private final RestaurantCsv restaurantCsv;
    private final MenuCsv menuCsv;
    private final ProductCsv productCsv;
    private final NutritionCsv nutritionCsv;
    private final IngredientsCsv ingredientsCsv;
    private final MenuItemCsv menuItemCsv;
    private final PhotoCsv photoCsv;
    private final MenuAssetCsv menuAssetCsv;
    private final CsvImportLogRepository importLog;

    public BundleInnerRunner(RestaurantCsv restaurantCsv, MenuCsv menuCsv, ProductCsv productCsv,
                             NutritionCsv nutritionCsv, IngredientsCsv ingredientsCsv,
                             MenuItemCsv menuItemCsv, PhotoCsv photoCsv, MenuAssetCsv menuAssetCsv,
                             CsvImportLogRepository importLog) {
        this.restaurantCsv = restaurantCsv;
        this.menuCsv = menuCsv;
        this.productCsv = productCsv;
        this.nutritionCsv = nutritionCsv;
        this.ingredientsCsv = ingredientsCsv;
        this.menuItemCsv = menuItemCsv;
        this.photoCsv = photoCsv;
        this.menuAssetCsv = menuAssetCsv;
        this.importLog = importLog;
    }

    public record InnerResult(CsvImportReport report, CsvImportLog logEntry) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InnerResult runOne(String slug, byte[] content, String filename, String actor,
                              boolean dryRun) throws IOException {
        CsvImportLog log = CsvImportLog.start(slug, filename, actor, dryRun);
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content),
                StandardCharsets.UTF_8)) {
            CsvImportReport report = switch (slug) {
                case "restaurants" -> restaurantCsv.parse(reader, dryRun);
                case "menus" -> menuCsv.parse(reader, dryRun);
                case "products" -> productCsv.parse(reader, dryRun);
                case "nutrition" -> nutritionCsv.parse(reader, dryRun);
                case "ingredients" -> ingredientsCsv.parse(reader, dryRun);
                case "menu-items" -> menuItemCsv.parse(reader, dryRun);
                case "photos" -> photoCsv.parse(reader, dryRun);
                case "menu-assets" -> menuAssetCsv.parse(reader, dryRun);
                default -> throw new IllegalArgumentException("Unknown CSV resource: " + slug);
            };
            log.finishOk(report.totalRows(), report.inserted(), report.updated(),
                    report.errors().size());
            importLog.save(log);
            return new InnerResult(report, log);
        } catch (RuntimeException | IOException e) {
            log.finishFailed(e.getMessage());
            importLog.save(log);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }
}
