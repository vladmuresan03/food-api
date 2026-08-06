package com.foodfinder.publicapi;

import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.restaurant.RestaurantStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read-side service for the public API. Composes data from multiple
 * repositories into the DTO shapes consumed by the public controller.
 */
@Service
public class PublicApiService {

    private final RestaurantRepository restaurants;
    private final MenuRepository menus;
    private final MenuItemRepository menuItems;
    private final ProductRepository products;
    private final PhotoRepository photos;

    public PublicApiService(RestaurantRepository restaurants, MenuRepository menus,
                            MenuItemRepository menuItems, ProductRepository products,
                            PhotoRepository photos) {
        this.restaurants = restaurants;
        this.menus = menus;
        this.menuItems = menuItems;
        this.products = products;
        this.photos = photos;
    }

    public List<Dtos.RestaurantSummary> listRestaurants(String q, String city,
                                                        RestaurantStatus status, int page, int size) {
        // For the public list we default to ACTIVE only.
        RestaurantStatus effective = (status == null) ? RestaurantStatus.ACTIVE : status;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        String cityFilter = (city == null || city.isBlank()) ? null : city;

        // Filter first, paginate after — paginating before means pages can
        // be sparse or empty when a filter narrows the result.
        return restaurants.findAll().stream()
                .filter(r -> r.getStatus() == effective)
                .filter(r -> cityFilter == null || cityFilter.equalsIgnoreCase(r.getCity()))
                .filter(r -> needle.isEmpty()
                        || r.getName().toLowerCase().contains(needle)
                        || r.getRestaurantKey().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Restaurant::getName, String.CASE_INSENSITIVE_ORDER))
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(r -> new Dtos.RestaurantSummary(
                        r.getRestaurantKey(), r.getName(), r.getCity(),
                        r.getLatitude(), r.getLongitude(),
                        r.getStatus().name(), r.getUpdatedAt()))
                .toList();
    }

    public Dtos.RestaurantDetail restaurantDetail(String key) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        Photo primary = photos
                .findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(r.getId())
                .orElse(null);
        long productCount = products.countByRestaurantId(r.getId());
        List<Menu> published = menus.findByRestaurantIdAndStatus(r.getId(), MenuStatus.PUBLISHED);
        List<Dtos.MenuSummary> menuDtos = published.stream()
                .sorted(Comparator.comparing(Menu::getName, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new Dtos.MenuSummary(
                        m.getMenuKey(), m.getName(),
                        m.getMenuType() == null ? null : m.getMenuType().name(),
                        m.getValidFrom(), m.getValidTo()))
                .toList();
        return new Dtos.RestaurantDetail(
                r.getRestaurantKey(), r.getName(), r.getWebsiteUrl(), r.getAddressLine(), r.getCity(),
                r.getLatitude(), r.getLongitude(),
                r.getStatus().name(),
                primary == null ? null : photoContentUrl(primary.getPhotoKey()),
                primary == null ? null : thumbnailUrl(primary.getPhotoKey()),
                (int) productCount,
                menuDtos);
    }

    public List<Dtos.MenuSummary> listRestaurantMenus(String restaurantKey) {
        Restaurant r = restaurants.findByRestaurantKey(restaurantKey)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + restaurantKey));
        return menus.findByRestaurantIdAndStatus(r.getId(), MenuStatus.PUBLISHED).stream()
                .sorted(Comparator.comparing(Menu::getName, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new Dtos.MenuSummary(
                        m.getMenuKey(), m.getName(),
                        m.getMenuType() == null ? null : m.getMenuType().name(),
                        m.getValidFrom(), m.getValidTo()))
                .toList();
    }

    public Dtos.MenuDetail menuDetail(String menuKey) {
        Menu menu = menus.findByMenuKey(menuKey)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + menuKey));
        if (menu.getStatus() != MenuStatus.PUBLISHED) {
            throw new NoSuchElementException("Menu not published: " + menuKey);
        }
        Restaurant r = restaurants.findById(menu.getRestaurantId())
                .orElseThrow(() -> new NoSuchElementException("Restaurant gone: " + menu.getRestaurantId()));
        List<MenuItem> items = menuItems.findByMenuIdOrderBySortOrderAsc(menu.getId());
        // Group items by section, preserving first-seen order. Each item
        // resolves its product + primary photo. Photo URLs are keyed by
        // photo_key (not product_key).
        Map<String, java.util.List<Dtos.Item>> grouped = new LinkedHashMap<>();
        for (MenuItem mi : items) {
            Product p = products.findById(mi.getProductId()).orElse(null);
            if (p == null) continue;
            Photo primary = photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId()).orElse(null);
            Dtos.ImageRef img = (primary == null) ? null
                    : new Dtos.ImageRef(photoContentUrl(primary.getPhotoKey()),
                            thumbnailUrl(primary.getPhotoKey()));
            Dtos.Item item = new Dtos.Item(
                    p.getProductKey(), p.getName(), p.getDescription(),
                    mi.getPrice(), mi.getCurrency(), p.getWeightText(),
                    mi.isAvailable(), img);
            grouped.computeIfAbsent(mi.getSectionName(), k -> new java.util.ArrayList<>()).add(item);
        }
        List<Dtos.Section> sections = grouped.entrySet().stream()
                .map(e -> new Dtos.Section(e.getKey(), e.getValue()))
                .toList();
        return new Dtos.MenuDetail(
                menu.getMenuKey(), menu.getName(),
                new Dtos.RestaurantRef(r.getRestaurantKey(), r.getName()),
                sections);
    }

    public List<Dtos.ProductSummary> listProducts(String q, String restaurantKey, String menuKey,
                                                  String section, BigDecimal minPrice, BigDecimal maxPrice,
                                                  Boolean hasPhoto, Boolean available,
                                                  int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        // For the simple filters we can push them down to the repository
        // (restaurant scope and text match). The rest (menu/section/price/
        // availability/has-photo) are evaluated in-memory because the cross-
        // table filtering would otherwise require either Specifications or
        // many narrow query methods, neither of which is worth the cost for
        // a public read endpoint with this volume.
        Iterable<Product> source = (restaurantKey != null && !restaurantKey.isBlank())
                ? products.findByRestaurantId(restaurantIdFor(restaurantKey).orElse(-1L))
                : products.findAll();
        java.util.List<Product> filtered = new java.util.ArrayList<>();
        for (Product p : source) {
            if (!needle.isEmpty()
                    && !p.getName().toLowerCase().contains(needle)
                    && !p.getProductKey().toLowerCase().contains(needle)) {
                continue;
            }
            if (menuKey != null && !menuKey.isBlank()
                    && !productIdsInMenu(menuKey).contains(p.getId())) {
                continue;
            }
            if (section != null && !section.isBlank()
                    && !productMatchesSection(p.getId(), section)) {
                continue;
            }
            if ((minPrice != null || maxPrice != null)
                    && !productPriceInRange(p.getId(), minPrice, maxPrice)) {
                continue;
            }
            if (Boolean.TRUE.equals(available) && !productAvailable(p.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(hasPhoto)
                    && photos.findByRestaurantIdAndProductIdAndStatus(
                            p.getRestaurantId(), p.getId(), PhotoStatus.ACTIVE).isEmpty()) {
                continue;
            }
            filtered.add(p);
        }
        // Filter first, then paginate. Sorting globally here matches the
        // legacy Sort.by("name"); before this fix the page was sorted only
        // within itself, so page 2 could hold items that alphabetically
        // belong on page 0.
        return filtered.stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(this::toProductSummary)
                .toList();
    }

    private java.util.Optional<Long> restaurantIdFor(String key) {
        return restaurants.findByRestaurantKey(key).map(Restaurant::getId);
    }

    private List<Long> productIdsInMenu(String menuKey) {
        return menus.findByMenuKey(menuKey)
                .map(m -> menuItems.findByMenuIdOrderBySortOrderAsc(m.getId()).stream()
                        .map(MenuItem::getProductId).toList())
                .orElse(List.of());
    }

    private boolean productMatchesSection(Long productId, String section) {
        return menuItems.findByProductId(productId).stream()
                .anyMatch(mi -> section.equals(mi.getSectionName()));
    }

    private boolean productPriceInRange(Long productId, BigDecimal min, BigDecimal max) {
        return menuItems.findByProductId(productId).stream().anyMatch(mi ->
                (min == null || (mi.getPrice() != null && mi.getPrice().compareTo(min) >= 0))
                        && (max == null || (mi.getPrice() != null && mi.getPrice().compareTo(max) <= 0)));
    }

    private boolean productAvailable(Long productId) {
        return menuItems.findByProductId(productId).stream().anyMatch(MenuItem::isAvailable);
    }

    public Dtos.ProductDetail productDetail(String productKey) {
        Product p = products.findByProductKey(productKey)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productKey));
        Restaurant r = restaurants.findById(p.getRestaurantId())
                .orElseThrow(() -> new NoSuchElementException("Restaurant gone: " + p.getRestaurantId()));
        List<MenuItem> items = menuItems.findByProductId(p.getId());
        List<Dtos.MenuAppearance> appearances = items.stream()
                .map(mi -> {
                    Menu m = menus.findById(mi.getMenuId()).orElse(null);
                    return new Dtos.MenuAppearance(
                            m == null ? "" : m.getMenuKey(),
                            m == null ? "" : m.getName(),
                            mi.getSectionName(), mi.getPrice(), mi.getCurrency(), mi.isAvailable());
                })
                .toList();
        List<Photo> photoList = photos.findByRestaurantIdAndProductIdAndStatus(
                p.getRestaurantId(), p.getId(), PhotoStatus.ACTIVE);
        List<Dtos.Photo> photoDtos = photoList.stream()
                .map(ph -> new Dtos.Photo(ph.getPhotoKey(),
                        photoContentUrl(ph.getPhotoKey()),
                        thumbnailUrl(ph.getPhotoKey()),
                        ph.getAltText(), ph.isPrimaryPhoto()))
                .toList();
        Photo primary = photoList.stream().filter(Photo::isPrimaryPhoto).findFirst().orElse(null);
        return new Dtos.ProductDetail(
                p.getProductKey(), p.getName(), p.getDescription(),
                p.getWeightText(),
                p.getStatus() == null ? null : p.getStatus().name(),
                new Dtos.RestaurantRef(r.getRestaurantKey(), r.getName()),
                appearances, photoDtos,
                primary == null ? null : thumbnailUrl(primary.getPhotoKey()));
    }

    private Dtos.ProductSummary toProductSummary(Product p) {
        Restaurant r = restaurants.findById(p.getRestaurantId()).orElse(null);
        // best-effort first menu_item's section/price (consumer-friendly compact form)
        List<MenuItem> items = menuItems.findByProductId(p.getId());
        String section = items.isEmpty() ? null : items.get(0).getSectionName();
        BigDecimal price = items.isEmpty() ? null : items.get(0).getPrice();
        String currency = items.isEmpty() ? "RON" : items.get(0).getCurrency();
        boolean available = !items.isEmpty() && items.get(0).isAvailable();
        Photo primary = photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId()).orElse(null);
        boolean hasPhoto = primary != null;
        return new Dtos.ProductSummary(
                p.getProductKey(), p.getName(),
                r == null ? "" : r.getRestaurantKey(),
                r == null ? "" : r.getName(),
                section, price, currency, p.getWeightText(), available, hasPhoto,
                primary == null ? null : thumbnailUrl(primary.getPhotoKey()));
    }

    // ------------------------------------------------------------------ URL helpers
    //
    // The photo content and thumbnail endpoints (phase 5) will live at these paths.
    // The DTOs embed these so the consumer has a single source of truth for URLs.

    public static String photoContentUrl(String photoKey) {
        return "/api/photos/" + photoKey + "/content";
    }

    public static String thumbnailUrl(String photoKey) {
        return "/api/photos/" + photoKey + "/thumbnail";
    }
}
