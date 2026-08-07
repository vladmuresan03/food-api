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
            ImageRef image) {
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
            String primaryPhotoThumbnailUrl) {
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
