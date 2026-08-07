package com.foodfinder.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Public DTOs — separate from JPA entities. Records, immutable, JSON-friendly.
 */
public final class Dtos {

    private Dtos() {
    }

    public record RestaurantSummary(
            String key,
            String name,
            String city,
            BigDecimal latitude,
            BigDecimal longitude,
            String status,
            Instant updatedAt) {
    }

    public record RestaurantDetail(
            String key,
            String name,
            String websiteUrl,
            String addressLine,
            String city,
            BigDecimal latitude,
            BigDecimal longitude,
            String status,
            String primaryPhotoUrl,
            String primaryPhotoThumbnailUrl,
            int productCount,
            List<MenuSummary> menus) {
    }

    public record MenuSummary(
            String key,
            String name,
            String type,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    public record MenuDetail(
            String key,
            String name,
            RestaurantRef restaurant,
            List<Section> sections) {
    }

    public record RestaurantRef(
            String key,
            String name) {
    }

    public record Section(
            String name,
            List<Item> items) {
    }

    public record Item(
            String productKey,
            String name,
            String description,
            BigDecimal price,
            String currency,
            String weight,
            Integer weightGrams,
            String category,
            String tags,
            Integer spiceLevel,
            boolean available,
            ImageRef image,
            Nutrition nutrition,
            List<Ingredient> ingredients,
            Dietary dietary) {
    }

    public record ImageRef(
            String url,
            String thumbnailUrl) {
    }

    public record ProductSummary(
            String key,
            String name,
            String restaurantKey,
            String restaurantName,
            String section,
            BigDecimal price,
            String currency,
            String weight,
            Integer weightGrams,
            String category,
            String tags,
            boolean available,
            boolean hasPhoto,
            String thumbnailUrl) {
    }

    public record ProductDetail(
            String key,
            String name,
            String description,
            String weight,
            Integer weightGrams,
            String category,
            String tags,
            String status,
            RestaurantRef restaurant,
            List<MenuAppearance> menuAppearances,
            List<Photo> photos,
            String primaryPhotoThumbnailUrl,
            Nutrition nutrition,
            List<Ingredient> ingredients,
            Dietary dietary) {
    }

    /**
     * EU 1169/2011 Anex XIV mandatory nutrition declaration. All fields
     * nullable — a "?" in the consumer UI means the restaurant has not
     * declared the value. {@code basis} tells the UI whether the numbers
     * are per 100g, per 100ml, or per declared portion.
     */
    public record Nutrition(
            String basis,
            java.math.BigDecimal energyKcal,
            java.math.BigDecimal fatG,
            java.math.BigDecimal satFatG,
            java.math.BigDecimal carbsG,
            java.math.BigDecimal sugarsG,
            java.math.BigDecimal proteinG,
            java.math.BigDecimal saltG,
            java.math.BigDecimal fiberG,
            String sourceUrl,
            String lastVerifiedAt) {
    }

    /**
     * One row from {@code product_ingredient}, in display order. The
     * allergen flag is denormalized for index-only reads; the code is
     * the source of truth for the consumer app's bold/icon overlay.
     */
    public record Ingredient(
            int position,
            String name,
            boolean isAllergen,
            String allergenCode,
            java.math.BigDecimal percentage,
            String originCountry) {
    }

    /**
     * Computed from the structured ingredient list (never stored). Always
     * accurate, regardless of how stale the manual "vegetarian" flag on
     * the product would be.
     */
    public record Dietary(
            boolean vegan,
            boolean vegetarian,
            boolean glutenFree) {
    }

    public record MenuAppearance(
            String menuKey,
            String menuName,
            String section,
            BigDecimal price,
            String currency,
            boolean available) {
    }

    public record Photo(
            String key,
            String url,
            String thumbnailUrl,
            String alt,
            boolean primary) {
    }
}
