package com.foodfinder.admin;

import com.foodfinder.csv.CsvImportReport;
import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuAsset;
import com.foodfinder.menu.MenuAssetRepository;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-side assembly for the Thymeleaf admin tables. Composes data from
 * multiple repositories into the row records the templates expect.
 */
@Service
public class AdminViewService {

    private final RestaurantRepository restaurants;
    private final MenuRepository menus;
    private final MenuItemRepository menuItems;
    private final MenuAssetRepository menuAssets;
    private final ProductRepository products;
    private final PhotoRepository photos;

    public AdminViewService(RestaurantRepository restaurants, MenuRepository menus,
                            MenuItemRepository menuItems, MenuAssetRepository menuAssets,
                            ProductRepository products, PhotoRepository photos) {
        this.restaurants = restaurants;
        this.menus = menus;
        this.menuItems = menuItems;
        this.menuAssets = menuAssets;
        this.products = products;
        this.photos = photos;
    }

    public List<RestaurantRow> listRestaurants(String q, String city, String status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return restaurants.findAll().stream()
                .filter(r -> needle.isEmpty()
                        || r.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || r.getRestaurantKey().toLowerCase(Locale.ROOT).contains(needle))
                .filter(r -> city == null || city.isBlank() || city.equalsIgnoreCase(r.getCity()))
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.getStatus().name()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(r -> new RestaurantRow(
                        r.getId(), r.getRestaurantKey(), r.getName(), r.getCity(),
                        r.getLatitude(), r.getLongitude(), r.getStatus().name(),
                        (int) menus.findByRestaurantIdOrderByCreatedAtAsc(r.getId()).size(),
                        (int) products.countByRestaurantId(r.getId()),
                        r.getUpdatedAt()))
                .toList();
    }

    public List<MenuRow> listMenus(String q, String restaurantKey, String status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> restaurantKeyById = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getRestaurantKey));
        return menus.findAll().stream()
                .filter(m -> needle.isEmpty()
                        || m.getMenuKey().toLowerCase(Locale.ROOT).contains(needle)
                        || m.getName().toLowerCase(Locale.ROOT).contains(needle))
                .filter(m -> restaurantKey == null || restaurantKey.isBlank()
                        || restaurantKey.equals(restaurantKeyById.get(m.getRestaurantId())))
                .filter(m -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(m.getStatus().name()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(m -> new MenuRow(
                        m,
                        restaurantKeyById.getOrDefault(m.getRestaurantId(), ""),
                        (int) menuItems.countByMenuId(m.getId()),
                        (int) menuAssets.countByMenuId(m.getId())))
                .toList();
    }

    public List<ProductRow> listProducts(String q, String restaurantKey, String status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> restaurantKeyById = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getRestaurantKey));
        return products.findAll().stream()
                .filter(p -> needle.isEmpty()
                        || p.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || p.getProductKey().toLowerCase(Locale.ROOT).contains(needle))
                .filter(p -> restaurantKey == null || restaurantKey.isBlank()
                        || restaurantKey.equals(restaurantKeyById.get(p.getRestaurantId())))
                .filter(p -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(p.getStatus().name()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(p -> {
                    String thumb = photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId())
                            .map(ph -> "/api/photos/" + ph.getPhotoKey() + "/thumbnail")
                            .orElse(null);
                    return new ProductRow(
                            p.getId(), p.getProductKey(), p.getName(),
                            restaurantKeyById.getOrDefault(p.getRestaurantId(), ""),
                            p.getWeightText(), p.getStatus().name(),
                            p.getUpdatedAt(), thumb);
                })
                .toList();
    }

    public List<MenuItemRow> listMenuItems(String menuKey, String productKey, String section) {
        Map<Long, String> menuKeyById = menus.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, Menu::getMenuKey));
        Map<Long, String> productNameById = products.findAll().stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));
        Map<Long, String> productKeyById = products.findAll().stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductKey));
        Map<Long, String> restaurantKeyById = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getRestaurantKey));
        Map<Long, String> weightByProductId = products.findAll().stream()
                .collect(Collectors.toMap(Product::getId, p -> p.getWeightText() == null ? "" : p.getWeightText()));
        Map<Long, String> productThumbById = products.findAll().stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        p -> photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId())
                                .map(ph -> "/api/photos/" + ph.getPhotoKey() + "/thumbnail")
                                // Collectors.toMap rejects null values; default to
                                // empty string and let the template show the
                                // "no thumbnail" state via #strings.isEmpty.
                                .orElse(""),
                        (a, b) -> a));

        return menuItems.findAll().stream()
                .map(MenuItem::getMenuId).distinct()
                .flatMap(mid -> menuItems.findByMenuIdOrderBySortOrderAsc(mid).stream())
                .filter(mi -> menuKey == null || menuKey.isBlank()
                        || menuKey.equals(menuKeyById.get(mi.getMenuId())))
                .filter(mi -> productKey == null || productKey.isBlank()
                        || productKey.equals(productKeyById.get(mi.getProductId())))
                .filter(mi -> section == null || section.isBlank()
                        || section.equalsIgnoreCase(mi.getSectionName()))
                .map(mi -> new MenuItemRow(
                        mi.getId(),
                        menuKeyById.getOrDefault(mi.getMenuId(), ""),
                        productKeyById.getOrDefault(mi.getProductId(), ""),
                        productNameById.getOrDefault(mi.getProductId(), ""),
                        restaurantKeyById.getOrDefault(mi.getRestaurantId(), ""),
                        mi.getSectionName(),
                        mi.getPrice(),
                        mi.getCurrency(),
                        weightByProductId.getOrDefault(mi.getProductId(), ""),
                        mi.isAvailable(),
                        mi.getUpdatedAt(),
                        productThumbById.getOrDefault(mi.getProductId(), null)))
                .toList();
    }

    public List<PhotoRow> listPhotos(String restaurantKey, String productKey, String status) {
        Map<Long, String> restaurantKeyById = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getRestaurantKey));
        Map<Long, String> productKeyById = products.findAll().stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductKey));
        return photos.findAll().stream()
                .filter(p -> restaurantKey == null || restaurantKey.isBlank()
                        || restaurantKey.equals(restaurantKeyById.get(p.getRestaurantId())))
                .filter(p -> productKey == null || productKey.isBlank()
                        || productKey.equals(p.getProductId() == null ? "" : productKeyById.get(p.getProductId())))
                .filter(p -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(p.getStatus().name()))
                .sorted((a, b) -> a.getPhotoKey().compareTo(b.getPhotoKey()))
                .map(p -> new PhotoRow(
                        p.getPhotoKey(),
                        restaurantKeyById.getOrDefault(p.getRestaurantId(), ""),
                        p.getProductId() == null ? null : productKeyById.get(p.getProductId()),
                        p.getSourceType().name(),
                        p.isPrimaryPhoto(),
                        p.getStatus().name(),
                        p.getStorageKey() == null ? null : "/api/photos/" + p.getPhotoKey() + "/content",
                        "/api/photos/" + p.getPhotoKey() + "/thumbnail"))
                .toList();
    }

    public List<MenuAssetRow> listMenuAssets() {
        Map<Long, String> menuKeyById = menus.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, Menu::getMenuKey));
        return menuAssets.findAll().stream()
                .sorted((a, b) -> a.getAssetKey().compareTo(b.getAssetKey()))
                .map(a -> {
                    String label = a.getOriginalFilename() != null ? a.getOriginalFilename() : a.getAssetKey();
                    String contentUrl = a.getStorageKey() == null ? null
                            : "/api/menus/" + menuKeyById.getOrDefault(a.getMenuId(), "")
                            + "/assets/" + a.getAssetKey() + "/content";
                    return new MenuAssetRow(
                            a.getAssetKey(),
                            menuKeyById.getOrDefault(a.getMenuId(), ""),
                            a.getAssetType().name(),
                            a.getOriginalFilename(),
                            a.getMimeType(),
                            a.getSizeBytes(),
                            a.getSha256() == null ? "" : a.getSha256().substring(0, Math.min(12, a.getSha256().length())),
                            a.getSourceUrl(),
                            contentUrl,
                            label,
                            a.getCreatedAt());
                })
                .toList();
    }

    // ------------------------------------------------------------------ row records

    public record RestaurantRow(Long id, String restaurantKey, String name, String city,
                                java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                String status, int menuCount, int productCount, Instant updatedAt) {
    }

    public record MenuRow(Menu menu, String restaurantKey, int itemCount, int assetCount) {
    }

    public record ProductRow(Long id, String productKey, String name, String restaurantKey,
                             String weightText, String status, Instant updatedAt, String thumbnailUrl) {
    }

    public record MenuItemRow(Long id, String menuKey, String productKey, String productName,
                              String restaurantKey, String sectionName, java.math.BigDecimal price,
                              String currency, String weight, boolean available, Instant updatedAt,
                              String thumbnailUrl) {
    }

    public record PhotoRow(String photoKey, String restaurantKey, String productKey,
                           String sourceType, boolean primary, String status,
                           String contentUrl, String thumbnailUrl) {
    }

    public record MenuAssetRow(String assetKey, String menuKey, String assetType,
                               String originalFilename, String mimeType, Long sizeBytes,
                               String sha256Short, String sourceUrl, String contentUrl,
                               String label, Instant createdAt) {
    }

    public record CsvResourceRow(String slug, String label) {
    }

    public List<CsvResourceRow> csvResources() {
        return List.of(
                new CsvResourceRow("restaurants", "restaurants.csv"),
                new CsvResourceRow("menus", "menus.csv"),
                new CsvResourceRow("products", "products.csv"),
                new CsvResourceRow("nutrition", "nutrition.csv"),
                new CsvResourceRow("ingredients", "ingredients.csv"),
                new CsvResourceRow("menu-items", "menu_items.csv"),
                new CsvResourceRow("photos", "photos.csv"),
                new CsvResourceRow("menu-assets", "menu_assets.csv"));
    }
}
