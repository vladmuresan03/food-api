package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductNutrition;
import com.foodfinder.product.ProductNutritionRepository;
import com.foodfinder.product.ProductRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * REST CRUD for the 1:1 nutrition overlay on a {@link Product}. The
 * same record (one row per product) is updated by the CSV bulk path
 * and the admin form; the REST API mirrors what the CSV does but with
 * a JSON body for one-off edits.
 */
@RestController
@RequestMapping("/admin/api/products/{productKey}/nutrition")
public class AdminNutritionController {

    private static final Set<String> ALLOWED_BASIS = Set.of("per_100g", "per_100ml", "per_portion");

    private final ProductRepository products;
    private final ProductNutritionRepository nutritions;

    public AdminNutritionController(ProductRepository products, ProductNutritionRepository nutritions) {
        this.products = products;
        this.nutritions = nutritions;
    }

    @GetMapping
    public ResponseEntity<NutritionView> get(@PathVariable String productKey) {
        Product p = loadProduct(productKey);
        return nutritions.findById(p.getId())
                .map(n -> ResponseEntity.ok(NutritionView.of(n)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    public NutritionView put(@PathVariable String productKey, @RequestBody NutritionUpsert body,
                             Authentication auth) {
        Product p = loadProduct(productKey);
        if (body.basis != null && !ALLOWED_BASIS.contains(body.basis)) {
            throw new AdminConflictException(
                    "basis must be one of " + ALLOWED_BASIS + " (got '" + body.basis + "')");
        }
        validateNonNegative(body.energyKcal, "energy_kcal");
        validateNonNegative(body.fatG, "fat_g");
        validateNonNegative(body.satFatG, "sat_fat_g");
        validateNonNegative(body.carbsG, "carbs_g");
        validateNonNegative(body.sugarsG, "sugars_g");
        validateNonNegative(body.proteinG, "protein_g");
        validateNonNegative(body.saltG, "salt_g");
        validateNonNegative(body.fiberG, "fiber_g");
        Instant lastVerified = null;
        if (body.lastVerifiedAt != null && !body.lastVerifiedAt.isBlank()) {
            try {
                lastVerified = LocalDate.parse(body.lastVerifiedAt)
                        .atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                throw new AdminConflictException(
                        "last_verified_at must be ISO-8601 date (YYYY-MM-DD): " + body.lastVerifiedAt);
            }
        }
        ProductNutrition existing = nutritions.findById(p.getId()).orElse(null);
        ProductNutrition n = existing == null ? new ProductNutrition() : existing;
        n.setProductId(p.getId());
        n.setBasis(body.basis == null ? "per_100g" : body.basis);
        n.setEnergyKcal(body.energyKcal);
        n.setFatG(body.fatG);
        n.setSatFatG(body.satFatG);
        n.setCarbsG(body.carbsG);
        n.setSugarsG(body.sugarsG);
        n.setProteinG(body.proteinG);
        n.setSaltG(body.saltG);
        n.setFiberG(body.fiberG);
        n.setSourceUrl(blankToNull(body.sourceUrl));
        n.setLastVerifiedAt(lastVerified);
        n.setUpdatedBy(auth == null ? null : auth.getName());
        nutritions.save(n);
        return NutritionView.of(n);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String productKey) {
        Product p = loadProduct(productKey);
        if (!nutritions.existsById(p.getId())) {
            return ResponseEntity.notFound().build();
        }
        nutritions.deleteById(p.getId());
        return ResponseEntity.noContent().build();
    }

    private Product loadProduct(String key) {
        return products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
    }

    private static void validateNonNegative(BigDecimal v, String field) {
        if (v != null && v.signum() < 0) {
            throw new AdminConflictException(field + " must not be negative: " + v.toPlainString());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record NutritionUpsert(
            String basis,
            BigDecimal energyKcal,
            BigDecimal fatG,
            BigDecimal satFatG,
            BigDecimal carbsG,
            BigDecimal sugarsG,
            BigDecimal proteinG,
            BigDecimal saltG,
            BigDecimal fiberG,
            String sourceUrl,
            String lastVerifiedAt) {
    }

    public record NutritionView(
            String productKey,
            String basis,
            BigDecimal energyKcal,
            BigDecimal fatG,
            BigDecimal satFatG,
            BigDecimal carbsG,
            BigDecimal sugarsG,
            BigDecimal proteinG,
            BigDecimal saltG,
            BigDecimal fiberG,
            String sourceUrl,
            String lastVerifiedAt) {
        static NutritionView of(ProductNutrition n) {
            return new NutritionView(
                    null,
                    n.getBasis(),
                    n.getEnergyKcal(), n.getFatG(), n.getSatFatG(),
                    n.getCarbsG(), n.getSugarsG(), n.getProteinG(),
                    n.getSaltG(), n.getFiberG(),
                    n.getSourceUrl(),
                    n.getLastVerifiedAt() == null ? null
                            : n.getLastVerifiedAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
    }
}
