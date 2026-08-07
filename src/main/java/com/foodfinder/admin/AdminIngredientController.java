package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.product.AllergenCode;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductIngredient;
import com.foodfinder.product.ProductIngredientId;
import com.foodfinder.product.ProductIngredientRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST CRUD for the ordered ingredient list of a {@link Product}.
 * "Replace all" semantics: PUT replaces the whole list with the
 * incoming one. Same model as the CSV importer; consistent with how
 * restaurants actually update ingredients (a supplier reformulates,
 * the whole list is resent, not patched row-by-row).
 */
@RestController
@RequestMapping("/admin/api/products/{productKey}/ingredients")
public class AdminIngredientController {

    private static final int MAX_INGREDIENTS_PER_PRODUCT = 50;

    private final ProductRepository products;
    private final ProductIngredientRepository ingredients;

    public AdminIngredientController(ProductRepository products,
                                     ProductIngredientRepository ingredients) {
        this.products = products;
        this.ingredients = ingredients;
    }

    @GetMapping
    public List<IngredientView> list(@PathVariable String productKey) {
        Product p = loadProduct(productKey);
        return ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId())
                .stream()
                .map(IngredientView::of)
                .toList();
    }

    @PutMapping
    public List<IngredientView> replaceAll(@PathVariable String productKey,
                                           @RequestBody List<IngredientUpsert> body,
                                           Authentication auth) {
        Product p = loadProduct(productKey);
        if (body == null) {
            body = List.of();
        }
        replaceAllInternal(p, body);
        if (auth != null) {
            // No-op audit marker: the principal is captured at the
            // controller boundary even though we don't persist
            // per-row audit info on the ingredients table.
            auth.getName();
        }
        return ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId())
                .stream()
                .map(IngredientView::of)
                .toList();
    }

    /**
     * Validate + write the full ingredient list for a product. Shared
     * by the REST PUT (above) and the form POST handler in
     * {@link AdminViewController}, so the two paths cannot drift.
     */
    private void replaceAllInternal(Product p, List<IngredientUpsert> body) {
        if (body.size() > MAX_INGREDIENTS_PER_PRODUCT) {
            throw new AdminConflictException(
                    "Too many ingredients (" + body.size() + " > " + MAX_INGREDIENTS_PER_PRODUCT + ")");
        }
        // Validate before any write so a bad row doesn't leave a half-updated list.
        for (int i = 0; i < body.size(); i++) {
            IngredientUpsert u = body.get(i);
            if (u.position() == null || u.position() < 1 || u.position() > MAX_INGREDIENTS_PER_PRODUCT) {
                throw new AdminConflictException(
                        "ingredient[" + i + "].position must be 1.." + MAX_INGREDIENTS_PER_PRODUCT);
            }
            if (u.name() == null || u.name().isBlank()) {
                throw new AdminConflictException("ingredient[" + i + "].name is required");
            }
            if (u.name().length() > 200) {
                throw new AdminConflictException(
                        "ingredient[" + i + "].name must be at most 200 characters");
            }
            if (u.allergenCode() != null && !u.allergenCode().isBlank()
                    && !AllergenCode.ALL_CODES.contains(u.allergenCode().toLowerCase())) {
                throw new AdminConflictException(
                        "ingredient[" + i + "].allergen_code must be one of " + AllergenCode.ALL_CODES
                                + " (got '" + u.allergenCode() + "')");
            }
            boolean isAllergen = u.isAllergen() != null && u.isAllergen();
            if (isAllergen && (u.allergenCode() == null || u.allergenCode().isBlank())) {
                throw new AdminConflictException(
                        "ingredient[" + i + "] has is_allergen=true but no allergen_code");
            }
            if (!isAllergen && u.allergenCode() != null && !u.allergenCode().isBlank()) {
                throw new AdminConflictException(
                        "ingredient[" + i + "] has allergen_code but is_allergen=false");
            }
            if (u.percentage() != null
                    && (u.percentage().signum() < 0
                    || u.percentage().compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new AdminConflictException(
                        "ingredient[" + i + "].percentage must be 0..100");
            }
            if (u.originCountry() != null && u.originCountry().length() != 2) {
                throw new AdminConflictException(
                        "ingredient[" + i + "].origin_country must be ISO 3166-1 alpha-2");
            }
        }
        // Deduplicate by position; the last entry for a given position wins.
        java.util.Map<Integer, IngredientUpsert> byPosition = new java.util.LinkedHashMap<>();
        for (IngredientUpsert u : body) {
            byPosition.put(u.position(), u);
        }

        ingredients.deleteByProductId(p.getId());
        for (IngredientUpsert u : byPosition.values()) {
            ProductIngredient pi = new ProductIngredient();
            pi.setId(new ProductIngredientId(p.getId(), u.position().shortValue()));
            pi.setName(u.name());
            boolean isAllergen = u.isAllergen() != null && u.isAllergen();
            pi.setAllergen(isAllergen);
            pi.setAllergenCode(isAllergen && u.allergenCode() != null
                    ? u.allergenCode().toLowerCase() : null);
            pi.setPercentage(u.percentage());
            pi.setOriginCountry(u.originCountry());
            ingredients.save(pi);
        }
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@PathVariable String productKey) {
        Product p = loadProduct(productKey);
        ingredients.deleteByProductId(p.getId());
        return ResponseEntity.noContent().build();
    }

    private Product loadProduct(String key) {
        return products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
    }

    /**
     * Package-public entry point used by the form POST handler. Same
     * validation + write as the REST endpoint, but skips the HTTP
     * plumbing and lets the caller handle success/failure UI.
     */
    public List<IngredientView> replaceAll(Product p, List<IngredientUpsert> body) {
        replaceAllInternal(p, body);
        return ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId())
                .stream()
                .map(IngredientView::of)
                .toList();
    }

    public record IngredientUpsert(
            Integer position,
            String name,
            Boolean isAllergen,
            String allergenCode,
            BigDecimal percentage,
            String originCountry) {
    }

    public record IngredientView(
            int position,
            String name,
            boolean isAllergen,
            String allergenCode,
            BigDecimal percentage,
            String originCountry) {
        static IngredientView of(ProductIngredient pi) {
            return new IngredientView(
                    pi.getId().getPosition(),
                    pi.getName(),
                    pi.isAllergen(),
                    pi.getAllergenCode(),
                    pi.getPercentage(),
                    pi.getOriginCountry());
        }
    }
}
