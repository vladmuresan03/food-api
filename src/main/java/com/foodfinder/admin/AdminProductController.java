package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.product.ProductStatus;
import com.foodfinder.restaurant.RestaurantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/admin/api/products")
public class AdminProductController {

    private final ProductRepository products;
    private final RestaurantRepository restaurants;

    public AdminProductController(ProductRepository products, RestaurantRepository restaurants) {
        this.products = products;
        this.restaurants = restaurants;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.findAll().stream().map(ProductView::of).toList();
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@RequestBody @Valid ProductUpsert body,
                                             Authentication auth) {
        if (products.existsByProductKey(body.productKey())) {
            throw new AdminConflictException("product_key already exists: " + body.productKey());
        }
        Long restaurantId = restaurants.findByRestaurantKey(body.restaurantKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + body.restaurantKey()))
                .getId();
        Product p = new Product();
        apply(p, body, restaurantId);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        return ResponseEntity.ok(ProductView.of(p));
    }

    @GetMapping("/{productKey}")
    public ProductView get(@PathVariable String productKey) {
        return ProductView.of(loadOrThrow(productKey));
    }

    @PutMapping("/{productKey}")
    public ProductView update(@PathVariable String productKey, @RequestBody @Valid ProductUpsert body,
                             Authentication auth) {
        Product p = loadOrThrow(productKey);
        Long restaurantId = restaurants.findByRestaurantKey(body.restaurantKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + body.restaurantKey()))
                .getId();
        apply(p, body, restaurantId);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        return ProductView.of(p);
    }

    @PatchMapping("/{productKey}/status")
    public ProductView updateStatus(@PathVariable String productKey, @RequestBody ProductStatusUpdate body,
                                    Authentication auth) {
        Product p = loadOrThrow(productKey);
        p.setStatus(body.status());
        p.setUpdatedBy(actor(auth));
        products.save(p);
        return ProductView.of(p);
    }

    private static String actor(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private Product loadOrThrow(String key) {
        return products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
    }

    /**
     * Hard delete. Cascades to menu_items, photos, product_nutrition
     * and product_ingredient (V8 FK CASCADE + V6 FK CASCADE on the
     * overlays). Use only when the product should not exist at all;
     * for normal "this product is no longer served", prefer
     * {@code PATCH /{key}/status} with status=ARCHIVED.
     *
     * <p>Two-step guard: the product must be {@code ARCHIVED} first.
     * Server-side enforcement so a direct POST/curl can't bypass
     * the UI's button visibility check.</p>
     */
    @DeleteMapping("/{productKey}")
    public ResponseEntity<Void> hardDelete(@PathVariable String productKey) {
        Product p = loadOrThrow(productKey);
        if (p.getStatus() != ProductStatus.ARCHIVED) {
            throw new AdminConflictException(
                    "Hard delete requires the product to be archived first. "
                            + "Archive '" + productKey + "' before deleting. Current status: " + p.getStatus());
        }
        products.delete(p);
        return ResponseEntity.noContent().build();
    }

    private void apply(Product p, ProductUpsert body, Long restaurantId) {
        p.setProductKey(body.productKey());
        p.setRestaurantId(restaurantId);
        p.setName(body.name());
        p.setDescription(body.description());
        p.setWeightText(blankToNull(body.weightText()));
        p.setWeightGrams(body.weightGrams());
        p.setCategory(blankToNull(body.category()));
        p.setTags(blankToNull(body.tags()));
        p.setStatus(body.status() == null ? ProductStatus.DRAFT : body.status());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record ProductUpsert(
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String productKey,
            @NotBlank String restaurantKey,
            @NotBlank String name,
            String description,
            String weightText,
            Integer weightGrams,
            String category,
            String tags,
            ProductStatus status) {
    }

    public record ProductStatusUpdate(ProductStatus status) {
    }

    public record ProductView(
            Long id, String productKey, Long restaurantId, String name, String description,
            String weightText, Integer weightGrams, String category, String tags, String status) {
        static ProductView of(Product p) {
            return new ProductView(p.getId(), p.getProductKey(), p.getRestaurantId(), p.getName(),
                    p.getDescription(), p.getWeightText(), p.getWeightGrams(),
                    p.getCategory(), p.getTags(),
                    p.getStatus() == null ? null : p.getStatus().name());
        }
    }
}
