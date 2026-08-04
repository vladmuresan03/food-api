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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by("name").ascending());
        // simple derived query: filter by status, optional city, optional name LIKE q
        // Spring Data JPA Specifications would be nicer, but we keep it simple.
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        List<Restaurant> all = restaurants.findAll(pageable).getContent().stream()
                .filter(r -> r.getStatus() == effective)
                .filter(r -> city == null || city.isBlank() || city.equalsIgnoreCase(r.getCity()))
                .filter(r -> needle.isEmpty()
                        || r.getName().toLowerCase().contains(needle)
                        || r.getRestaurantKey().toLowerCase().contains(needle))
                .toList();
        return all.stream()
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
        // group by section, preserving first-seen order
        Map<String, java.util.List<Dtos.Item>> grouped = new LinkedHashMap<>();
        for (MenuItem mi : items) {
            Product p = products.findById(mi.getProductId()).orElse(null);
            if (p == null) continue;
            Photo primary = photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId()).orElse(null);
            Dtos.Item item = new Dtos.Item(
                    p.getProductKey(), p.getName(), p.getDescription(),
                    mi.getPrice(), mi.getCurrency(), p.getWeightText(),
                    mi.isAvailable(),
                    primary == null ? null : new Dtos.ImageRef(
                            photoContentUrl(p.getProductKey() + "/" + primary.getPhotoKey()),
                            thumbnailUrl(p.getProductKey() + "/" + primary.getPhotoKey())));
            // (the URL helpers above receive photo_key, not product_key — fix below)
            grouped.computeIfAbsent(mi.getSectionName(), k -> new java.util.ArrayList<>()).add(item);
        }
        // rebuild items with correct image URLs (photo URLs are by photo_key, not product_key)
        grouped.clear();
        for (MenuItem mi : items) {
            Product p = products.findById(mi.getProductId()).orElse(null);
            if (p == null) continue;
            Photo primary = photos.findFirstByProductIdAndPrimaryPhotoTrue(p.getId()).orElse(null);
            Dtos.ImageRef img = null;
            if (primary != null) {
                img = new Dtos.ImageRef(photoContentUrl(primary.getPhotoKey()),
                        thumbnailUrl(primary.getPhotoKey()));
            }
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
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by("name").ascending());
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        // Start from all products, then narrow.
        // For filtering by menuKey/section/minPrice/maxPrice we need to consider menu_item rows.
        Iterable<Product> all = products.findAll(pageable);
        java.util.List<Product> filtered = new java.util.ArrayList<>();
        for (Product p : all) {
            if (needle.isEmpty() || p.getName().toLowerCase().contains(needle)
                    || p.getProductKey().toLowerCase().contains(needle)) {
                filtered.add(p);
            }
        }
        if (restaurantKey != null && !restaurantKey.isBlank()) {
            Restaurant r = restaurants.findByRestaurantKey(restaurantKey).orElse(null);
            if (r == null) return List.of();
            Long rid = r.getId();
            filtered = filtered.stream().filter(p -> rid.equals(p.getRestaurantId())).toList();
        }
        if (menuKey != null && !menuKey.isBlank()) {
            Menu m = menus.findByMenuKey(menuKey).orElse(null);
            if (m == null) return List.of();
            List<Long> productIds = menuItems.findByMenuIdOrderBySortOrderAsc(m.getId()).stream()
                    .map(MenuItem::getProductId).toList();
            filtered = filtered.stream().filter(p -> productIds.contains(p.getId())).toList();
        }
        if (section != null && !section.isBlank()) {
            String sec = section;
            filtered = filtered.stream().filter(p -> {
                List<MenuItem> items = menuItems.findByProductId(p.getId());
                return items.stream().anyMatch(mi -> sec.equals(mi.getSectionName()));
            }).toList();
        }
        if (minPrice != null || maxPrice != null) {
            filtered = filtered.stream().filter(p -> {
                List<MenuItem> items = menuItems.findByProductId(p.getId());
                return items.stream().anyMatch(mi ->
                        (minPrice == null || (mi.getPrice() != null && mi.getPrice().compareTo(minPrice) >= 0))
                                && (maxPrice == null || (mi.getPrice() != null && mi.getPrice().compareTo(maxPrice) <= 0)));
            }).toList();
        }
        if (Boolean.TRUE.equals(available)) {
            filtered = filtered.stream().filter(p -> {
                List<MenuItem> items = menuItems.findByProductId(p.getId());
                return items.stream().anyMatch(MenuItem::isAvailable);
            }).toList();
        }
        if (Boolean.TRUE.equals(hasPhoto)) {
            filtered = filtered.stream().filter(p ->
                    !photos.findByRestaurantIdAndProductIdAndStatus(p.getRestaurantId(), p.getId(), PhotoStatus.ACTIVE)
                            .isEmpty()).toList();
        }
        return filtered.stream()
                .map(this::toProductSummary)
                .toList();
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
