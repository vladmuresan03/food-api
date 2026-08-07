package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.restaurant.RestaurantStatus;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/admin/api/restaurants")
public class AdminRestaurantController {

    private final RestaurantRepository repository;

    public AdminRestaurantController(RestaurantRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<RestaurantView> list(@RequestParam(required = false) String status) {
        List<Restaurant> all = (status == null || status.isBlank())
                ? repository.findAll()
                : repository.findAll().stream()
                .filter(r -> r.getStatus().name().equalsIgnoreCase(status))
                .toList();
        return all.stream().map(RestaurantView::of).toList();
    }

    @PostMapping
    public ResponseEntity<RestaurantView> create(@RequestBody @Valid RestaurantUpsert body,
                                                 Authentication auth) {
        if (repository.existsByRestaurantKey(body.restaurantKey())) {
            throw new AdminConflictException("restaurant_key already exists: " + body.restaurantKey());
        }
        Restaurant r = new Restaurant();
        apply(r, body);
        r.setUpdatedBy(actor(auth));
        repository.save(r);
        return ResponseEntity.ok(RestaurantView.of(r));
    }

    @GetMapping("/{restaurantKey}")
    public RestaurantView get(@PathVariable String restaurantKey) {
        return RestaurantView.of(loadOrThrow(restaurantKey));
    }

    @PutMapping("/{restaurantKey}")
    public RestaurantView update(@PathVariable String restaurantKey,
                                 @RequestBody @Valid RestaurantUpsert body,
                                 Authentication auth) {
        Restaurant r = loadOrThrow(restaurantKey);
        apply(r, body);
        r.setUpdatedBy(actor(auth));
        repository.save(r);
        return RestaurantView.of(r);
    }

    @PatchMapping("/{restaurantKey}/status")
    public RestaurantView updateStatus(@PathVariable String restaurantKey,
                                       @RequestBody StatusUpdate body,
                                       Authentication auth) {
        Restaurant r = loadOrThrow(restaurantKey);
        r.setStatus(body.status());
        r.setUpdatedBy(actor(auth));
        repository.save(r);
        return RestaurantView.of(r);
    }

    private static String actor(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private Restaurant loadOrThrow(String key) {
        return repository.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
    }

    private void apply(Restaurant r, RestaurantUpsert body) {
        r.setRestaurantKey(body.restaurantKey());
        r.setName(body.name());
        r.setWebsiteUrl(body.websiteUrl());
        r.setAddressLine(body.addressLine());
        r.setCity(body.city() == null || body.city().isBlank() ? "Cluj-Napoca" : body.city());
        r.setLatitude(body.latitude());
        r.setLongitude(body.longitude());
        r.setStatus(body.status() == null ? RestaurantStatus.DRAFT : body.status());
    }

    /**
     * Hard delete. Cascades through V8's ON DELETE CASCADE FKs to
     * menus, products, menu_items, photos, menu_assets, plus the
     * product_nutrition and product_ingredient overlays added in V6.
     * Use only for GDPR right-to-be-forgotten or accidental-import
     * cleanup; for normal "this restaurant is closed", prefer
     * {@code PATCH /{key}/status} with status=ARCHIVED.
     *
     * <p>Two-step guard: the entity must be {@code ARCHIVED} first.
     * This is enforced server-side so a direct POST/curl can't bypass
     * the UI's button visibility check.</p>
     */
    @DeleteMapping("/{restaurantKey}")
    public ResponseEntity<Void> hardDelete(@PathVariable String restaurantKey) {
        Restaurant r = repository.findByRestaurantKey(restaurantKey)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + restaurantKey));
        requireArchived(r.getStatus(), restaurantKey);
        repository.delete(r);
        return ResponseEntity.noContent().build();
    }

    private static void requireArchived(RestaurantStatus status, String key) {
        if (status != RestaurantStatus.ARCHIVED) {
            throw new AdminConflictException(
                    "Hard delete requires the entity to be archived first. "
                            + "Archive '" + key + "' before deleting. Current status: " + status);
        }
    }

    // ------------------------------------------------------------------ DTOs

    public record RestaurantUpsert(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                    message = "must be lowercase slug")
            String restaurantKey,
            @NotBlank String name,
            String websiteUrl,
            String addressLine,
            String city,
            BigDecimal latitude,
            BigDecimal longitude,
            RestaurantStatus status) {
    }

    public record StatusUpdate(RestaurantStatus status) {
    }

    public record RestaurantView(
            Long id,
            String restaurantKey,
            String name,
            String websiteUrl,
            String addressLine,
            String city,
            BigDecimal latitude,
            BigDecimal longitude,
            String status) {
        static RestaurantView of(Restaurant r) {
            return new RestaurantView(r.getId(), r.getRestaurantKey(), r.getName(),
                    r.getWebsiteUrl(), r.getAddressLine(), r.getCity(),
                    r.getLatitude(), r.getLongitude(),
                    r.getStatus() == null ? null : r.getStatus().name());
        }
    }
}
