package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.csv.CsvImportReport;
import com.foodfinder.csv.IngredientsCsv;
import com.foodfinder.csv.MenuAssetCsv;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.NutritionCsv;
import com.foodfinder.csv.PhotoCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.menu.MenuType;
import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoSourceType;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.photo.PhotoStorageService;
import com.foodfinder.product.AllergenCode;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductIngredient;
import com.foodfinder.product.ProductIngredientRepository;
import com.foodfinder.product.ProductNutrition;
import com.foodfinder.product.ProductNutritionRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.product.ProductStatus;
import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.restaurant.RestaurantStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Server-rendered admin pages. Form-login + Basic auth (configured in
 * SecurityConfig) gates everything under /admin/**.
 *
 * <p>Form-based POST handlers (create / update / status transitions) live
 * here, alongside the page renderers. They are intentionally separate from
 * the JSON {@code Admin*Controller} classes, which serve {@code /admin/api/**}
 * with {@code ProblemDetail} responses. The HTML form flow needs redirect-
 * after-POST semantics and re-renders with field-level error messages, so
 * the two surfaces share entities but not handlers.
 */
@Controller
@RequestMapping("/admin")
public class AdminViewController {

    private final AdminViewService views;
    private final RestaurantCsv restaurantCsv;
    private final MenuCsv menuCsv;
    private final ProductCsv productCsv;
    private final NutritionCsv nutritionCsv;
    private final IngredientsCsv ingredientsCsv;
    private final MenuItemCsv menuItemCsv;
    private final PhotoCsv photoCsv;
    private final MenuAssetCsv menuAssetCsv;
    private final RestaurantRepository restaurants;
    private final MenuRepository menus;
    private final ProductRepository products;
    private final ProductNutritionRepository nutritions;
    private final ProductIngredientRepository ingredients;
    private final MenuItemRepository menuItems;
    private final PhotoRepository photoRepository;
    private final PhotoStorageService photoStorage;
    private final CsvImportLogRepository importLog;
    private final BundleImporter bundleImporter;
    private final CsvPreviewService previewService;
    private final AdminIngredientController adminIngredients;

    public AdminViewController(AdminViewService views, RestaurantCsv restaurantCsv, MenuCsv menuCsv,
                               ProductCsv productCsv, NutritionCsv nutritionCsv,
                               IngredientsCsv ingredientsCsv, MenuItemCsv menuItemCsv,
                               PhotoCsv photoCsv, MenuAssetCsv menuAssetCsv,
                               RestaurantRepository restaurants, MenuRepository menus,
                               ProductRepository products,
                               ProductNutritionRepository nutritions,
                               ProductIngredientRepository ingredients,
                               MenuItemRepository menuItems,
                               PhotoRepository photoRepository,
                               PhotoStorageService photoStorage,
                               CsvImportLogRepository importLog, BundleImporter bundleImporter,
                               CsvPreviewService previewService,
                               AdminIngredientController adminIngredients) {
        this.views = views;
        this.restaurantCsv = restaurantCsv;
        this.menuCsv = menuCsv;
        this.productCsv = productCsv;
        this.nutritionCsv = nutritionCsv;
        this.ingredientsCsv = ingredientsCsv;
        this.menuItemCsv = menuItemCsv;
        this.photoCsv = photoCsv;
        this.menuAssetCsv = menuAssetCsv;
        this.restaurants = restaurants;
        this.menus = menus;
        this.products = products;
        this.nutritions = nutritions;
        this.ingredients = ingredients;
        this.menuItems = menuItems;
        this.photoRepository = photoRepository;
        this.photoStorage = photoStorage;
        this.importLog = importLog;
        this.bundleImporter = bundleImporter;
        this.previewService = previewService;
        this.adminIngredients = adminIngredients;
    }

    // ------------------------------------------------------------------ pages

    @GetMapping
    public String home() {
        return "admin/home";
    }

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping("/restaurants")
    public String restaurants(@RequestParam(required = false) String q,
                              @RequestParam(required = false) String city,
                              @RequestParam(required = false) String status,
                              Model model) {
        model.addAttribute("rows", views.listRestaurants(q, city, status));
        model.addAttribute("q", q);
        model.addAttribute("city", city);
        model.addAttribute("status", status);
        return "admin/restaurants";
    }

    @GetMapping("/menus")
    public String menus(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String restaurantKey,
                        @RequestParam(required = false) String status,
                        Model model) {
        model.addAttribute("rows", views.listMenus(q, restaurantKey, status));
        model.addAttribute("q", q);
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("status", status);
        return "admin/menus";
    }

    @GetMapping("/products")
    public String products(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String restaurantKey,
                           @RequestParam(required = false) String status,
                           Model model) {
        model.addAttribute("rows", views.listProducts(q, restaurantKey, status));
        model.addAttribute("q", q);
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("status", status);
        return "admin/products";
    }

    @GetMapping("/menu-items")
    public String menuItems(@RequestParam(required = false) String menuKey,
                            @RequestParam(required = false) String productKey,
                            @RequestParam(required = false) String section,
                            Model model) {
        model.addAttribute("rows", views.listMenuItems(menuKey, productKey, section));
        model.addAttribute("menuKey", menuKey);
        model.addAttribute("productKey", productKey);
        model.addAttribute("section", section);
        return "admin/menu-items";
    }

    @GetMapping("/photos")
    public String photos(@RequestParam(required = false) String restaurantKey,
                         @RequestParam(required = false) String productKey,
                         @RequestParam(required = false) String status,
                         Model model) {
        model.addAttribute("rows", views.listPhotos(restaurantKey, productKey, status));
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("productKey", productKey);
        model.addAttribute("status", status);
        return "admin/photos";
    }

    // ------------------------------------------------------------------ photo edit (reassign / alt / primary / status)

    /**
     * Render the photo edit form. Lets the operator reassign a photo to a
     * different product (or to the restaurant level), change the alt text,
     * toggle the primary flag, or archive / un-archive the row.
     */
    @GetMapping("/photos/{photoKey}/edit")
    public String editPhoto(@PathVariable("photoKey") String photoKey, Model model) {
        Photo p = photoRepository.findByPhotoKey(photoKey)
                .orElseThrow(() -> new NoSuchElementException("Photo not found: " + photoKey));
        // Pre-compute the restaurant and product keys for the template.
        String restaurantKey = restaurants.findById(p.getRestaurantId())
                .map(Restaurant::getRestaurantKey).orElse(null);
        String currentProductKey = p.getProductId() == null ? null
                : products.findById(p.getProductId()).map(Product::getProductKey).orElse(null);
        model.addAttribute("p", p);
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("currentProductKey", currentProductKey);
        model.addAttribute("statuses", PhotoStatus.values());
        model.addAttribute("sourceTypes", PhotoSourceType.values());
        return "admin/photo-form";
    }

    @PostMapping("/photos/{photoKey}")
    public String updatePhoto(@PathVariable("photoKey") String photoKey,
                              @RequestParam(required = false) String productKey,
                              @RequestParam(required = false) String altText,
                              @RequestParam(required = false) Boolean isPrimary,
                              @RequestParam(required = false) PhotoStatus status,
                              Authentication auth,
                              RedirectAttributes ra) {
        Photo p = photoRepository.findByPhotoKey(photoKey)
                .orElseThrow(() -> new NoSuchElementException("Photo not found: " + photoKey));
        // Normalise productKey=null to productKey="" so the service treats it
        // as "no product" (restaurant-level) rather than "don't change".
        String normalisedProductKey = (productKey != null && productKey.isBlank()) ? "" : productKey;
        p = photoStorage.update(photoKey, normalisedProductKey, altText, isPrimary, status, actor(auth));
        ra.addFlashAttribute("successMessage", "Photo '" + photoKey + "' updated");
        return "redirect:/admin/photos";
    }

    // ------------------------------------------------------------------ per-context upload (product or menu item)

    /**
     * Render the upload form for a specific product. Pre-fills both
     * {@code restaurantKey} and {@code productKey} so the operator only has
     * to pick a file (or paste from clipboard).
     */
    @GetMapping("/products/{productKey}/photos/new")
    public String uploadForProduct(@PathVariable("productKey") String productKey, Model model) {
        Product p = products.findByProductKey(productKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + productKey));
        String restaurantKey = restaurants.findById(p.getRestaurantId())
                .map(Restaurant::getRestaurantKey).orElse(null);
        if (restaurantKey == null) {
            throw new NoSuchElementException("Could not resolve restaurant for product " + productKey);
        }
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("productKey", productKey);
        model.addAttribute("menuItemId", null);
        model.addAttribute("sourceTypes", PhotoSourceType.values());
        return "admin/photo-upload";
    }

    /**
     * Render the upload form at the restaurant level (no product binding).
     * Useful for hero / cover / fallback gallery photos.
     */
    @GetMapping("/restaurants/{restaurantKey}/photos/new")
    public String uploadForRestaurant(@PathVariable("restaurantKey") String restaurantKey, Model model) {
        if (restaurants.findByRestaurantKey(restaurantKey).isEmpty()) {
            throw new NoSuchElementException("Unknown restaurant_key: " + restaurantKey);
        }
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("productKey", null);
        model.addAttribute("menuItemId", null);
        model.addAttribute("sourceTypes", PhotoSourceType.values());
        return "admin/photo-upload";
    }

    /**
     * Render the upload form from a menu-item row. Resolves the
     * {@code productKey} and {@code restaurantKey} from the menu item
     * automatically so the operator doesn't have to type either.
     */
    @GetMapping("/menu-items/{id}/photos/new")
    public String uploadForMenuItem(@PathVariable("id") Long id, Model model) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        String productKey = products.findById(mi.getProductId())
                .map(Product::getProductKey).orElse(null);
        String restaurantKey = productKey == null ? null
                : products.findByProductKey(productKey)
                .flatMap(p -> restaurants.findById(p.getRestaurantId()))
                .map(Restaurant::getRestaurantKey).orElse(null);
        if (restaurantKey == null) {
            throw new NoSuchElementException("Could not resolve restaurant for menu item " + id);
        }
        model.addAttribute("restaurantKey", restaurantKey);
        model.addAttribute("productKey", productKey);
        model.addAttribute("menuItemId", id);
        model.addAttribute("sourceTypes", PhotoSourceType.values());
        return "admin/photo-upload";
    }

    @GetMapping("/menu-assets")
    public String menuAssets(Model model) {
        model.addAttribute("rows", views.listMenuAssets());
        return "admin/menu-assets";
    }

    @GetMapping("/csv")
    public String csv(Model model) {
        model.addAttribute("resources", views.csvResources());
        return "admin/csv";
    }

    // ------------------------------------------------------------------ restaurant CRUD

    @GetMapping("/restaurants/new")
    public String newRestaurant(Model model) {
        model.addAttribute("mode", "new");
        model.addAttribute("statuses", RestaurantStatus.values());
        return "admin/restaurant-form";
    }

    @GetMapping("/restaurants/{key}/edit")
    public String editRestaurant(@PathVariable("key") String key, Model model) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        model.addAttribute("mode", "edit");
        model.addAttribute("r", r);
        model.addAttribute("statuses", RestaurantStatus.values());
        return "admin/restaurant-form";
    }

    @PostMapping("/restaurants")
    public String createRestaurant(@RequestParam String restaurantKey,
                                   @RequestParam String name,
                                   @RequestParam(required = false) String websiteUrl,
                                   @RequestParam(required = false) String addressLine,
                                   @RequestParam(required = false) String city,
                                   @RequestParam(required = false) BigDecimal latitude,
                                   @RequestParam(required = false) BigDecimal longitude,
                                   @RequestParam(required = false) RestaurantStatus status,
                                   Authentication auth,
                                   RedirectAttributes ra, Model model) {
        try {
            if (restaurants.existsByRestaurantKey(restaurantKey)) {
                throw new AdminConflictException("restaurant_key already exists: " + restaurantKey);
            }
            Restaurant r = new Restaurant();
            applyRestaurant(r, restaurantKey, name, websiteUrl, addressLine, city,
                    latitude, longitude, status);
            r.setUpdatedBy(actor(auth));
            restaurants.save(r);
            ra.addFlashAttribute("successMessage", "Restaurant '" + r.getRestaurantKey() + "' created");
            return "redirect:/admin/restaurants";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "new");
            model.addAttribute("statuses", RestaurantStatus.values());
            model.addAttribute("form", new RestaurantForm(restaurantKey, name, websiteUrl,
                    addressLine, city, latitude, longitude, status));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/restaurant-form";
        }
    }

    @PostMapping("/restaurants/{key}")
    public String updateRestaurant(@PathVariable("key") String key,
                                   @RequestParam String name,
                                   @RequestParam(required = false) String websiteUrl,
                                   @RequestParam(required = false) String addressLine,
                                   @RequestParam(required = false) String city,
                                   @RequestParam(required = false) BigDecimal latitude,
                                   @RequestParam(required = false) BigDecimal longitude,
                                   @RequestParam(required = false) RestaurantStatus status,
                                   Authentication auth,
                                   RedirectAttributes ra, Model model) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        try {
            applyRestaurant(r, key, name, websiteUrl, addressLine, city,
                    latitude, longitude, status);
            r.setUpdatedBy(actor(auth));
            restaurants.save(r);
            ra.addFlashAttribute("successMessage", "Restaurant '" + r.getRestaurantKey() + "' updated");
            return "redirect:/admin/restaurants";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "edit");
            model.addAttribute("r", r);
            model.addAttribute("statuses", RestaurantStatus.values());
            model.addAttribute("form", new RestaurantForm(key, name, websiteUrl, addressLine,
                    city, latitude, longitude, status));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/restaurant-form";
        }
    }

    @PostMapping("/restaurants/{key}/archive")
    public String archiveRestaurant(@PathVariable("key") String key, Authentication auth,
                                    RedirectAttributes ra) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        r.setStatus(RestaurantStatus.ARCHIVED);
        r.setUpdatedBy(actor(auth));
        restaurants.save(r);
        ra.addFlashAttribute("successMessage", "Restaurant '" + key + "' archived");
        return "redirect:/admin/restaurants";
    }

    @PostMapping("/restaurants/{key}/activate")
    public String activateRestaurant(@PathVariable("key") String key, Authentication auth,
                                     RedirectAttributes ra) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        r.setStatus(RestaurantStatus.ACTIVE);
        r.setUpdatedBy(actor(auth));
        restaurants.save(r);
        ra.addFlashAttribute("successMessage", "Restaurant '" + key + "' activated");
        return "redirect:/admin/restaurants";
    }

    /**
     * Hard delete (cascades through V8 FK CASCADE). Use for
     * GDPR right-to-be-forgotten or accidental-import cleanup; for
     * normal "closed for business" use the archive button instead.
     *
     * <p>Two-step guard: the restaurant must be {@code ARCHIVED}
     * first. The UI hides the button otherwise, but this is enforced
     * server-side so a direct POST/curl can't bypass the UI.</p>
     */
    @PostMapping("/restaurants/{key}/hard-delete")
    public String hardDeleteRestaurant(@PathVariable("key") String key, Authentication auth,
                                       RedirectAttributes ra) {
        Restaurant r = restaurants.findByRestaurantKey(key)
                .orElseThrow(() -> new NoSuchElementException("Restaurant not found: " + key));
        if (r.getStatus() != RestaurantStatus.ARCHIVED) {
            ra.addFlashAttribute("errorMessage",
                    "Hard delete requires the entity to be archived first. "
                            + "Archive '" + key + "' before deleting. Current status: " + r.getStatus());
            return "redirect:/admin/restaurants";
        }
        restaurants.delete(r);
        if (auth != null) auth.getName();
        ra.addFlashAttribute("successMessage", "Restaurant '" + key + "' permanently deleted");
        return "redirect:/admin/restaurants";
    }

    private static String actor(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private void applyRestaurant(Restaurant r, String restaurantKey, String name,
                                 String websiteUrl, String addressLine, String city,
                                 BigDecimal latitude, BigDecimal longitude,
                                 RestaurantStatus status) {
        if (restaurantKey == null || restaurantKey.isBlank()) {
            throw new IllegalArgumentException("restaurant_key is required");
        }
        if (!restaurantKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException(
                    "restaurant_key must be lowercase slug: " + restaurantKey);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be set together");
        }
        r.setRestaurantKey(restaurantKey);
        r.setName(name);
        r.setWebsiteUrl(blankToNull(websiteUrl));
        r.setAddressLine(blankToNull(addressLine));
        r.setCity(city == null || city.isBlank() ? "Cluj-Napoca" : city);
        r.setLatitude(latitude);
        r.setLongitude(longitude);
        r.setStatus(status == null ? RestaurantStatus.DRAFT : status);
    }

    public record RestaurantForm(String restaurantKey, String name, String websiteUrl,
                                 String addressLine, String city, BigDecimal latitude,
                                 BigDecimal longitude, RestaurantStatus status) {
    }

    // ------------------------------------------------------------------ menu CRUD

    @GetMapping("/menus/new")
    public String newMenu(Model model) {
        model.addAttribute("mode", "new");
        model.addAttribute("statuses", MenuStatus.values());
        model.addAttribute("menuTypes", MenuType.values());
        model.addAttribute("restaurants", views.listRestaurants(null, null, null));
        return "admin/menu-form";
    }

    @GetMapping("/menus/{key}/edit")
    public String editMenu(@PathVariable("key") String key, Model model) {
        Menu m = menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
        model.addAttribute("mode", "edit");
        model.addAttribute("m", m);
        model.addAttribute("statuses", MenuStatus.values());
        model.addAttribute("menuTypes", MenuType.values());
        model.addAttribute("restaurants", views.listRestaurants(null, null, null));
        return "admin/menu-form";
    }

    @PostMapping("/menus")
    public String createMenu(@RequestParam String menuKey,
                             @RequestParam String restaurantKey,
                             @RequestParam String name,
                             @RequestParam(required = false) MenuType menuType,
                             @RequestParam(required = false) MenuStatus status,
                             @RequestParam(required = false) String sourceUrl,
                             @RequestParam(required = false) String validFrom,
                             @RequestParam(required = false) String validTo,
                             Authentication auth,
                             RedirectAttributes ra, Model model) {
        try {
            if (menus.existsByMenuKey(menuKey)) {
                throw new AdminConflictException("menu_key already exists: " + menuKey);
            }
            Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                    .getId();
            LocalDate from = parseDateOrNull(validFrom, "valid_from");
            LocalDate to = parseDateOrNull(validTo, "valid_to");
            Menu m = new Menu();
            applyMenu(m, menuKey, restaurantId, name, menuType, status, sourceUrl, from, to);
            m.setUpdatedBy(actor(auth));
            menus.save(m);
            ra.addFlashAttribute("successMessage", "Menu '" + m.getMenuKey() + "' created");
            return "redirect:/admin/menus";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "new");
            model.addAttribute("statuses", MenuStatus.values());
            model.addAttribute("menuTypes", MenuType.values());
            model.addAttribute("restaurants", views.listRestaurants(null, null, null));
            model.addAttribute("form", new MenuForm(menuKey, restaurantKey, name, menuType, status,
                    sourceUrl, validFrom, validTo));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/menu-form";
        }
    }

    @PostMapping("/menus/{key}")
    public String updateMenu(@PathVariable("key") String key,
                             @RequestParam String restaurantKey,
                             @RequestParam String name,
                             @RequestParam(required = false) MenuType menuType,
                             @RequestParam(required = false) MenuStatus status,
                             @RequestParam(required = false) String sourceUrl,
                             @RequestParam(required = false) String validFrom,
                             @RequestParam(required = false) String validTo,
                             Authentication auth,
                             RedirectAttributes ra, Model model) {
        Menu m = menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
        try {
            Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                    .getId();
            LocalDate from = parseDateOrNull(validFrom, "valid_from");
            LocalDate to = parseDateOrNull(validTo, "valid_to");
            applyMenu(m, key, restaurantId, name, menuType, status, sourceUrl, from, to);
            if (status == MenuStatus.PUBLISHED && m.getPublishedAt() == null) {
                m.setPublishedAt(Instant.now());
            }
            m.setUpdatedBy(actor(auth));
            menus.save(m);
            ra.addFlashAttribute("successMessage", "Menu '" + m.getMenuKey() + "' updated");
            return "redirect:/admin/menus";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "edit");
            model.addAttribute("m", m);
            model.addAttribute("statuses", MenuStatus.values());
            model.addAttribute("menuTypes", MenuType.values());
            model.addAttribute("restaurants", views.listRestaurants(null, null, null));
            model.addAttribute("form", new MenuForm(key, restaurantKey, name, menuType, status,
                    sourceUrl, validFrom, validTo));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/menu-form";
        }
    }

    public record MenuForm(String menuKey, String restaurantKey, String name, MenuType menuType,
                           MenuStatus status, String sourceUrl, String validFrom, String validTo) {
    }

    @PostMapping("/menus/{key}/archive")
    public String archiveMenu(@PathVariable("key") String key, Authentication auth,
                              RedirectAttributes ra) {
        Menu m = menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
        m.setStatus(MenuStatus.ARCHIVED);
        m.setUpdatedBy(actor(auth));
        menus.save(m);
        ra.addFlashAttribute("successMessage", "Menu '" + key + "' archived");
        return "redirect:/admin/menus";
    }

    @PostMapping("/menus/{key}/activate")
    public String activateMenu(@PathVariable("key") String key, Authentication auth,
                               RedirectAttributes ra) {
        Menu m = menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
        m.setStatus(MenuStatus.PUBLISHED);
        m.setUpdatedBy(actor(auth));
        menus.save(m);
        ra.addFlashAttribute("successMessage", "Menu '" + key + "' activated");
        return "redirect:/admin/menus";
    }

    /**
     * Hard delete (cascades through V8 FK CASCADE to menu_items and
     * menu_assets). Use for cleanup of mistaken imports; for normal
     * "out of season" use the archive button instead.
     *
     * <p>Two-step guard: the menu must be {@code ARCHIVED} first.
     * Server-side enforcement so a direct POST/curl can't bypass
     * the UI.</p>
     */
    @PostMapping("/menus/{key}/hard-delete")
    public String hardDeleteMenu(@PathVariable("key") String key, Authentication auth,
                                 RedirectAttributes ra) {
        Menu m = menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
        if (m.getStatus() != MenuStatus.ARCHIVED) {
            ra.addFlashAttribute("errorMessage",
                    "Hard delete requires the menu to be archived first. "
                            + "Archive '" + key + "' before deleting. Current status: " + m.getStatus());
            return "redirect:/admin/menus";
        }
        menus.delete(m);
        if (auth != null) auth.getName();
        ra.addFlashAttribute("successMessage", "Menu '" + key + "' permanently deleted");
        return "redirect:/admin/menus";
    }

    private void applyMenu(Menu m, String menuKey, Long restaurantId, String name,
                           MenuType menuType, MenuStatus status, String sourceUrl,
                           LocalDate validFrom, LocalDate validTo) {
        if (menuKey == null || menuKey.isBlank()) {
            throw new IllegalArgumentException("menu_key is required");
        }
        if (!menuKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("menu_key must be lowercase slug: " + menuKey);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        m.setMenuKey(menuKey);
        m.setRestaurantId(restaurantId);
        m.setName(name);
        m.setMenuType(menuType == null ? MenuType.PERMANENT : menuType);
        m.setStatus(status == null ? MenuStatus.DRAFT : status);
        m.setSourceUrl(blankToNull(sourceUrl));
        m.setValidFrom(validFrom);
        m.setValidTo(validTo);
    }

    // ------------------------------------------------------------------ product CRUD

    @GetMapping("/products/new")
    public String newProduct(Model model) {
        model.addAttribute("mode", "new");
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("restaurants", views.listRestaurants(null, null, null));
        return "admin/product-form";
    }

    @GetMapping("/products/{key}/edit")
    public String editProduct(@PathVariable("key") String key, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        model.addAttribute("mode", "edit");
        model.addAttribute("p", p);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("restaurants", views.listRestaurants(null, null, null));
        return "admin/product-form";
    }

    @PostMapping("/products")
    public String createProduct(@RequestParam String productKey,
                                @RequestParam String restaurantKey,
                                @RequestParam String name,
                                @RequestParam(required = false) String description,
                                @RequestParam(required = false) String weightText,
                                @RequestParam(required = false) Integer weightGrams,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) String tags,
                                @RequestParam(required = false) ProductStatus status,
                                Authentication auth,
                                RedirectAttributes ra, Model model) {
        try {
            if (products.existsByProductKey(productKey)) {
                throw new AdminConflictException("product_key already exists: " + productKey);
            }
            Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                    .getId();
            Product p = new Product();
            applyProduct(p, productKey, restaurantId, name, description, weightText,
                    weightGrams, category, tags, status);
            p.setUpdatedBy(actor(auth));
            products.save(p);
            ra.addFlashAttribute("successMessage", "Product '" + p.getProductKey() + "' created");
            return "redirect:/admin/products";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "new");
            model.addAttribute("statuses", ProductStatus.values());
            model.addAttribute("restaurants", views.listRestaurants(null, null, null));
            model.addAttribute("form", new ProductForm(productKey, restaurantKey, name,
                    description, weightText, weightGrams, category, tags, status));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/product-form";
        }
    }

    @PostMapping("/products/{key}")
    public String updateProduct(@PathVariable("key") String key,
                                @RequestParam String restaurantKey,
                                @RequestParam String name,
                                @RequestParam(required = false) String description,
                                @RequestParam(required = false) String weightText,
                                @RequestParam(required = false) Integer weightGrams,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) String tags,
                                @RequestParam(required = false) ProductStatus status,
                                Authentication auth,
                                RedirectAttributes ra, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        try {
            Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                    .getId();
            applyProduct(p, key, restaurantId, name, description, weightText,
                    weightGrams, category, tags, status);
            p.setUpdatedBy(actor(auth));
            products.save(p);
            ra.addFlashAttribute("successMessage", "Product '" + p.getProductKey() + "' updated");
            return "redirect:/admin/products";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "edit");
            model.addAttribute("p", p);
            model.addAttribute("statuses", ProductStatus.values());
            model.addAttribute("restaurants", views.listRestaurants(null, null, null));
            model.addAttribute("form", new ProductForm(key, restaurantKey, name, description,
                    weightText, weightGrams, category, tags, status));
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/product-form";
        }
    }

    @PostMapping("/products/{key}/archive")
    public String archiveProduct(@PathVariable("key") String key, Authentication auth,
                                 RedirectAttributes ra) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        p.setStatus(ProductStatus.ARCHIVED);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        ra.addFlashAttribute("successMessage", "Product '" + key + "' archived");
        return "redirect:/admin/products";
    }

    @PostMapping("/products/{key}/activate")
    public String activateProduct(@PathVariable("key") String key, Authentication auth,
                                  RedirectAttributes ra) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        p.setStatus(ProductStatus.ACTIVE);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        ra.addFlashAttribute("successMessage", "Product '" + key + "' activated");
        return "redirect:/admin/products";
    }

    /**
     * Hard delete (cascades through V8 FK CASCADE to menu_items, photos,
     * product_nutrition, product_ingredient). Use for cleanup of
     * mistaken imports; for normal "no longer served" use the archive
     * button instead.
     *
     * <p>Two-step guard: the product must be {@code ARCHIVED} first.
     * Server-side enforcement so a direct POST/curl can't bypass
     * the UI.</p>
     */
    @PostMapping("/products/{key}/hard-delete")
    public String hardDeleteProduct(@PathVariable("key") String key, Authentication auth,
                                    RedirectAttributes ra) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        if (p.getStatus() != ProductStatus.ARCHIVED) {
            ra.addFlashAttribute("errorMessage",
                    "Hard delete requires the product to be archived first. "
                            + "Archive '" + key + "' before deleting. Current status: " + p.getStatus());
            return "redirect:/admin/products";
        }
        products.delete(p);
        if (auth != null) auth.getName();
        ra.addFlashAttribute("successMessage", "Product '" + key + "' permanently deleted");
        return "redirect:/admin/products";
    }

    // ------------------------------------------------------------------ product detail / nutrition / ingredients

    @GetMapping("/products/{key}")
    public String productDetail(@PathVariable("key") String key, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        Restaurant r = restaurants.findById(p.getRestaurantId()).orElse(null);
        ProductNutrition n = nutritions.findById(p.getId()).orElse(null);
        List<ProductIngredient> ings = ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId());
        model.addAttribute("p", p);
        model.addAttribute("restaurant", r);
        model.addAttribute("nutrition", n);
        model.addAttribute("ingredients", ings);
        model.addAttribute("photos", views.listPhotos(r == null ? null : r.getRestaurantKey(), key, "ACTIVE"));
        return "admin/product-detail";
    }

    @GetMapping("/products/{key}/nutrition")
    public String editNutrition(@PathVariable("key") String key, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        ProductNutrition n = nutritions.findById(p.getId()).orElse(null);
        model.addAttribute("p", p);
        model.addAttribute("nutrition", n);
        return "admin/product-nutrition-form";
    }

    @PostMapping("/products/{key}/nutrition")
    public String saveNutrition(@PathVariable("key") String key,
                                @RequestParam(required = false) String basis,
                                @RequestParam(required = false) BigDecimal energyKcal,
                                @RequestParam(required = false) BigDecimal fatG,
                                @RequestParam(required = false) BigDecimal satFatG,
                                @RequestParam(required = false) BigDecimal carbsG,
                                @RequestParam(required = false) BigDecimal sugarsG,
                                @RequestParam(required = false) BigDecimal proteinG,
                                @RequestParam(required = false) BigDecimal saltG,
                                @RequestParam(required = false) BigDecimal fiberG,
                                @RequestParam(required = false) String sourceUrl,
                                @RequestParam(required = false) String lastVerifiedAt,
                                Authentication auth,
                                RedirectAttributes ra, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        try {
            AdminNutritionController.NutritionUpsert upsert =
                    new AdminNutritionController.NutritionUpsert(basis, energyKcal, fatG, satFatG,
                            carbsG, sugarsG, proteinG, saltG, fiberG, sourceUrl, lastVerifiedAt);
            // The REST controller does the validation; we re-use it by
            // re-throwing on the 4xx. Since the REST controller writes
            // and we want to keep the form's re-render path consistent
            // (redirect on success), we mirror its logic here. A small
            // refactor would extract a shared service, but the duplication
            // is bounded and keeps the form path independent of the
            // REST controller's internals.
            ProductNutrition existing = nutritions.findById(p.getId()).orElse(null);
            ProductNutrition n = existing == null ? new ProductNutrition() : existing;
            applyNutrition(n, p.getId(), upsert);
            n.setUpdatedBy(actor(auth));
            nutritions.save(n);
            ra.addFlashAttribute("successMessage", "Nutrition saved for " + key);
            return "redirect:/admin/products/" + key;
        } catch (RuntimeException e) {
            model.addAttribute("p", p);
            ProductNutrition stale = nutritions.findById(p.getId()).orElse(null);
            model.addAttribute("nutrition", stale);
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/product-nutrition-form";
        }
    }

    private void applyNutrition(ProductNutrition n, Long productId,
                                AdminNutritionController.NutritionUpsert body) {
        if (body.basis() != null && !body.basis().isBlank()) {
            java.util.Set<String> allowed = java.util.Set.of("per_100g", "per_100ml", "per_portion");
            if (!allowed.contains(body.basis())) {
                throw new IllegalArgumentException(
                        "basis must be one of " + allowed + " (got '" + body.basis() + "')");
            }
        }
        rejectNegative(body.energyKcal(), "energy_kcal");
        rejectNegative(body.fatG(), "fat_g");
        rejectNegative(body.satFatG(), "sat_fat_g");
        rejectNegative(body.carbsG(), "carbs_g");
        rejectNegative(body.sugarsG(), "sugars_g");
        rejectNegative(body.proteinG(), "protein_g");
        rejectNegative(body.saltG(), "salt_g");
        rejectNegative(body.fiberG(), "fiber_g");
        java.time.Instant lastVerified = null;
        if (body.lastVerifiedAt() != null && !body.lastVerifiedAt().isBlank()) {
            try {
                lastVerified = java.time.LocalDate.parse(body.lastVerifiedAt())
                        .atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "last_verified_at must be ISO-8601 date (YYYY-MM-DD): "
                                + body.lastVerifiedAt());
            }
        }
        n.setProductId(productId);
        n.setBasis(body.basis() == null || body.basis().isBlank() ? "per_100g" : body.basis());
        n.setEnergyKcal(body.energyKcal());
        n.setFatG(body.fatG());
        n.setSatFatG(body.satFatG());
        n.setCarbsG(body.carbsG());
        n.setSugarsG(body.sugarsG());
        n.setProteinG(body.proteinG());
        n.setSaltG(body.saltG());
        n.setFiberG(body.fiberG());
        n.setSourceUrl(blankToNull(body.sourceUrl()));
        n.setLastVerifiedAt(lastVerified);
    }

    private static void rejectNegative(BigDecimal v, String field) {
        if (v != null && v.signum() < 0) {
            throw new IllegalArgumentException(
                    field + " must not be negative: " + v.toPlainString());
        }
    }

    @GetMapping("/products/{key}/ingredients")
    public String editIngredients(@PathVariable("key") String key, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        List<ProductIngredient> ings = ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId());
        model.addAttribute("p", p);
        model.addAttribute("ingredients", ings);
        model.addAttribute("allergenCodes", AllergenCode.ALL_CODES);
        return "admin/product-ingredients-form";
    }

    @PostMapping("/products/{key}/ingredients")
    public String saveIngredients(@PathVariable("key") String key,
                                  @RequestParam(required = false) java.util.List<String> ingName,
                                  @RequestParam(required = false) java.util.List<String> ingIsAllergen,
                                  @RequestParam(required = false) java.util.List<String> ingAllergenCode,
                                  @RequestParam(required = false) java.util.List<String> ingPercentage,
                                  @RequestParam(required = false) java.util.List<String> ingOriginCountry,
                                  Authentication auth,
                                  RedirectAttributes ra, Model model) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        try {
            // Index-aligned form fields. We coerce to 50 slots; missing
            // entries are nulls, which the loop skips.
            int size = 50;
            List<AdminIngredientController.IngredientUpsert> body = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) {
                String name = pick(ingName, i);
                if (name == null || name.isBlank()) {
                    continue; // empty row -> omitted
                }
                if (name.length() > 200) {
                    throw new IllegalArgumentException("Row " + (i + 1) + ": name must be at most 200 characters");
                }
                boolean isAllergen = "true".equalsIgnoreCase(pick(ingIsAllergen, i));
                String allergenCode = blankToNull(pick(ingAllergenCode, i));
                BigDecimal pct = parseDecimalOrNull(pick(ingPercentage, i), "percentage", i + 1);
                String country = blankToNull(pick(ingOriginCountry, i));
                if (country != null && country.length() != 2) {
                    throw new IllegalArgumentException(
                            "Row " + (i + 1) + ": origin_country must be ISO 3166-1 alpha-2 (2 letters)");
                }
                if (isAllergen && (allergenCode == null)) {
                    throw new IllegalArgumentException(
                            "Row " + (i + 1) + ": is_allergen is checked but no allergen_code is selected");
                }
                if (!isAllergen && allergenCode != null) {
                    throw new IllegalArgumentException(
                            "Row " + (i + 1) + ": allergen_code is set but is_allergen is not checked");
                }
                if (isAllergen && !AllergenCode.ALL_CODES.contains(allergenCode.toLowerCase())) {
                    throw new IllegalArgumentException(
                            "Row " + (i + 1) + ": allergen_code must be one of "
                                    + AllergenCode.ALL_CODES);
                }
                body.add(new AdminIngredientController.IngredientUpsert(
                        i + 1, name, isAllergen, allergenCode, pct, country));
            }
            // Delegate the actual write to the REST controller so the
            // form path and the REST API share one source of truth.
            adminIngredients.replaceAll(p, body);
            if (auth != null) auth.getName();
            ra.addFlashAttribute("successMessage",
                    "Ingredients saved for " + key + " (" + body.size() + " rows)");
            return "redirect:/admin/products/" + key;
        } catch (RuntimeException e) {
            List<ProductIngredient> stale = ingredients.findByIdProductIdOrderByIdPositionAsc(p.getId());
            model.addAttribute("p", p);
            model.addAttribute("ingredients", stale);
            model.addAttribute("allergenCodes", AllergenCode.ALL_CODES);
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/product-ingredients-form";
        }
    }

    private static String pick(java.util.List<String> list, int i) {
        if (list == null || i >= list.size()) return null;
        return list.get(i);
    }

    private static BigDecimal parseDecimalOrNull(String s, String field, int row) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Row " + row + ": " + field + " is not a number: " + s);
        }
    }

    private void applyProduct(Product p, String productKey, Long restaurantId, String name,
                              String description, String weightText, Integer weightGrams,
                              String category, String tags, ProductStatus status) {
        if (productKey == null || productKey.isBlank()) {
            throw new IllegalArgumentException("product_key is required");
        }
        if (!productKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("product_key must be lowercase slug: " + productKey);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (weightGrams != null && (weightGrams < 1 || weightGrams > 100000)) {
            throw new IllegalArgumentException(
                    "weight_grams must be between 1 and 100000: " + weightGrams);
        }
        String trimmedCategory = blankToNull(category);
        if (trimmedCategory != null && trimmedCategory.length() > 60) {
            throw new IllegalArgumentException(
                    "category must be at most 60 characters: " + trimmedCategory);
        }
        String normalizedTags = normalizeTags(tags);
        p.setProductKey(productKey);
        p.setRestaurantId(restaurantId);
        p.setName(name);
        p.setDescription(blankToNull(description));
        p.setWeightText(blankToNull(weightText));
        p.setWeightGrams(weightGrams);
        p.setCategory(trimmedCategory);
        p.setTags(normalizedTags);
        p.setStatus(status == null ? ProductStatus.DRAFT : status);
    }

    /**
     * Canonical tag allowlist for the {@code product.tags} column. Kept here
     * (not in the DB) so it can grow without a Flyway migration. The CSV
     * importer and the form both go through the same gate, so a typo cannot
     * pollute the index in {@code ix_product_category} or the public API
     * filter values.
     */
    static final Set<String> ALLOWED_TAGS = Set.of(
            // dietary
            "vegetarian", "vegan", "gluten-free", "lactose-free", "sugar-free", "low-sodium",
            // religious
            "halal", "kosher",
            // sourcing
            "bio", "local", "home-made",
            // preparation / flavor
            "fried", "grilled", "baked", "raw", "spicy", "hot", "sweet", "sour", "smoked"
    );

    /**
     * Comma-separated -> normalized, deduplicated, lowercased string. Throws
     * {@link IllegalArgumentException} listing the offending tag if the user
     * submitted anything outside {@link #ALLOWED_TAGS}. Returns {@code null}
     * for blank/null input (column is nullable).
     */
    static String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        List<String> valid = new ArrayList<>();
        for (String raw : tags.split(",")) {
            String t = raw.trim().toLowerCase();
            if (t.isEmpty()) {
                continue;
            }
            if (!ALLOWED_TAGS.contains(t)) {
                throw new IllegalArgumentException(
                        "tag '" + t + "' is not in the allowlist; allowed: " + ALLOWED_TAGS);
            }
            if (!valid.contains(t)) {
                valid.add(t);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        return String.join(",", valid);
    }

    public record ProductForm(
            String productKey,
            String restaurantKey,
            String name,
            String description,
            String weightText,
            Integer weightGrams,
            String category,
            String tags,
            ProductStatus status) {
    }

    // ------------------------------------------------------------------ menu-item CRUD

    @GetMapping("/menu-items/new")
    public String newMenuItem(@RequestParam(required = false) String menuKey, Model model) {
        model.addAttribute("mode", "new");
        model.addAttribute("menus", menus.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList());
        model.addAttribute("products", products.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList());
        model.addAttribute("prefillMenuKey", menuKey == null ? "" : menuKey);
        return "admin/menu-item-form";
    }

    @GetMapping("/menu-items/{id}/edit")
    public String editMenuItem(@PathVariable("id") Long id, Model model) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        model.addAttribute("mi", mi);
        return "admin/menu-item-form";
    }

    @PostMapping("/menu-items")
    public String createMenuItem(@RequestParam String menuKey,
                                 @RequestParam String productKey,
                                 @RequestParam(required = false) String sectionName,
                                 @RequestParam(required = false) BigDecimal price,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(required = false) Boolean available,
                                 @RequestParam(required = false) Integer sortOrder,
                                 @RequestParam(required = false) Integer spiceLevel,
                                 Authentication auth,
                                 RedirectAttributes ra, Model model) {
        try {
            if (menuKey == null || menuKey.isBlank()) {
                throw new IllegalArgumentException("menu_key is required");
            }
            if (productKey == null || productKey.isBlank()) {
                throw new IllegalArgumentException("product_key is required");
            }
            if (price != null && price.signum() < 0) {
                throw new IllegalArgumentException("price must not be negative");
            }
            validateSpiceLevel(spiceLevel);
            Long menuId = menus.findByMenuKey(menuKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown menu_key: " + menuKey)).getId();
            Long productId = products.findByProductKey(productKey)
                    .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + productKey)).getId();
            Long menuRestaurantId = menus.findById(menuId).orElseThrow().getRestaurantId();
            Long productRestaurantId = products.findById(productId).orElseThrow().getRestaurantId();
            if (!menuRestaurantId.equals(productRestaurantId)) {
                throw new AdminConflictException(
                        "menu_key and product_key belong to different restaurants");
            }
            if (menuItems.existsByMenuIdAndProductId(menuId, productId)) {
                throw new AdminConflictException(
                        "menu_item for (" + menuKey + "," + productKey + ") already exists");
            }
            MenuItem mi = new MenuItem();
            mi.setMenuId(menuId);
            mi.setProductId(productId);
            mi.setRestaurantId(menuRestaurantId);
            mi.setSectionName(sectionName == null || sectionName.isBlank() ? "Altele" : sectionName);
            mi.setPrice(price);
            mi.setCurrency(currency == null || currency.isBlank() ? "RON" : currency.toUpperCase());
            mi.setAvailable(available == null ? true : available);
            mi.setSortOrder(sortOrder == null ? 0 : sortOrder);
            mi.setSpiceLevel(spiceLevel);
            mi.setUpdatedBy(actor(auth));
            menuItems.save(mi);
            ra.addFlashAttribute("successMessage",
                    "Menu item added (" + menuKey + " x " + productKey + ")");
            return "redirect:/admin/menu-items";
        } catch (RuntimeException e) {
            model.addAttribute("mode", "new");
            model.addAttribute("menus", menus.findAll().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList());
            model.addAttribute("products", products.findAll().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList());
            model.addAttribute("prefillMenuKey", menuKey == null ? "" : menuKey);
            model.addAttribute("errorMessage", e.getMessage());
            return "admin/menu-item-form";
        }
    }

    @PostMapping("/menu-items/{id}")
    public String updateMenuItem(@PathVariable("id") Long id,
                                 @RequestParam String sectionName,
                                 @RequestParam(required = false) BigDecimal price,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(required = false) Boolean available,
                                 @RequestParam(required = false) Integer sortOrder,
                                 @RequestParam(required = false) Integer spiceLevel,
                                 Authentication auth,
                                 RedirectAttributes ra) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        if (sectionName == null || sectionName.isBlank()) {
            throw new IllegalArgumentException("section_name is required");
        }
        if (price != null && price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        validateSpiceLevel(spiceLevel);
        mi.setSectionName(sectionName);
        mi.setPrice(price);
        mi.setCurrency(currency == null || currency.isBlank() ? "RON" : currency.toUpperCase());
        mi.setAvailable(available == null ? mi.isAvailable() : available);
        mi.setSortOrder(sortOrder == null ? mi.getSortOrder() : sortOrder);
        mi.setSpiceLevel(spiceLevel);
        mi.setUpdatedBy(actor(auth));
        menuItems.save(mi);
        ra.addFlashAttribute("successMessage", "Menu item updated");
        return "redirect:/admin/menu-items";
    }

    private static void validateSpiceLevel(Integer spiceLevel) {
        if (spiceLevel != null && (spiceLevel < 0 || spiceLevel > 3)) {
            throw new IllegalArgumentException(
                    "spice_level must be between 0 and 3: " + spiceLevel);
        }
    }

    @PostMapping("/menu-items/{id}/archive")
    public String archiveMenuItem(@PathVariable("id") Long id, Authentication auth,
                                  RedirectAttributes ra) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        String productKey = lookupProductKey(mi);
        if (!mi.isAvailable()) {
            ra.addFlashAttribute("successMessage", "Menu item '" + productKey + "' is already hidden");
            return "redirect:/admin/menu-items";
        }
        mi.setAvailable(false);
        mi.setUpdatedBy(actor(auth));
        menuItems.save(mi);
        ra.addFlashAttribute("successMessage", "Menu item '" + productKey
                + "' hidden from consumers (reversible via Show)");
        return "redirect:/admin/menu-items";
    }

    @PostMapping("/menu-items/{id}/activate")
    public String activateMenuItem(@PathVariable("id") Long id, Authentication auth,
                                   RedirectAttributes ra) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        String productKey = lookupProductKey(mi);
        if (mi.isAvailable()) {
            ra.addFlashAttribute("successMessage", "Menu item '" + productKey + "' is already visible");
            return "redirect:/admin/menu-items";
        }
        mi.setAvailable(true);
        mi.setUpdatedBy(actor(auth));
        menuItems.save(mi);
        ra.addFlashAttribute("successMessage", "Menu item '" + productKey + "' visible again");
        return "redirect:/admin/menu-items";
    }

    /**
     * Hard delete. Use only when the menu item should not exist at all
     * (e.g. a CSV import that targeted the wrong menu). For normal
     * "stop showing this", prefer the Hide button instead.
     *
     * <p>Two-step guard: the menu item must be hidden ({@code available=false})
     * first. Server-side enforcement so a direct POST/curl can't bypass
     * the UI's button visibility check.</p>
     */
    @PostMapping("/menu-items/{id}/delete")
    public String deleteMenuItem(@PathVariable("id") Long id, Authentication auth,
                                 RedirectAttributes ra) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        String productKey = lookupProductKey(mi);
        if (mi.isAvailable()) {
            ra.addFlashAttribute("errorMessage",
                    "Hard delete requires the menu item to be hidden first. "
                            + "Hide '" + productKey + "' before deleting. Currently visible.");
            return "redirect:/admin/menu-items";
        }
        menuItems.delete(mi);
        ra.addFlashAttribute("successMessage", "Menu item '" + productKey + "' permanently deleted");
        return "redirect:/admin/menu-items";
    }

    private String lookupProductKey(MenuItem mi) {
        return products.findById(mi.getProductId())
                .map(p -> p.getProductKey())
                .orElse("#" + mi.getProductId());
    }

    // ------------------------------------------------------------------ CSV import submission

    @PostMapping("/csv/{slug}")
    public String importCsv(@PathVariable String slug,
                            @RequestParam("file") MultipartFile file,
                            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                            @RequestParam(value = "preview", defaultValue = "false") boolean preview,
                            Authentication auth,
                            Model model) throws IOException {
        if (preview) {
            CsvPreviewService.Preview p = previewService.previewFromBytes(file.getBytes());
            model.addAttribute("preview", p);
            model.addAttribute("previewSlug", slug);
            model.addAttribute("previewFilename", file.getOriginalFilename());
        } else {
            CsvImportReport report = runAndLog(slug, file.getOriginalFilename(), auth, dryRun, () -> {
                try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
                    return resolveImporter(slug).parse(reader, dryRun);
                }
            });
            model.addAttribute("report", report);
        }
        model.addAttribute("resources", views.csvResources());
        return "admin/csv";
    }

    @GetMapping("/imports")
    public String imports(Model model) {
        model.addAttribute("rows", importLog.findAllByOrderByStartedAtDesc(PageRequest.of(0, 100)));
        return "admin/imports";
    }

    // ------------------------------------------------------------------ bundle import

    @GetMapping("/csv/bundle")
    public String bundleForm(Model model) {
        return "admin/bundle";
    }

    @PostMapping("/csv/bundle")
    public String importBundle(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                               Authentication auth,
                               Model model) throws IOException {
        BundleImportResult result = bundleImporter.importBundle(
                file.getInputStream(), file.getOriginalFilename(), dryRun, auth);
        model.addAttribute("result", result);
        return "admin/bundle";
    }

    private CsvImportReport runAndLog(String slug, String filename, Authentication auth,
                                      boolean dryRun, CsvImporter fn) throws IOException {
        CsvImportLog log = CsvImportLog.start(slug, filename,
                auth == null ? "anonymous" : auth.getName(), dryRun);
        try {
            CsvImportReport report = fn.run();
            log.finishOk(report.totalRows(), report.inserted(), report.updated(),
                    report.errors().size());
            importLog.save(log);
            return report;
        } catch (RuntimeException | IOException e) {
            log.finishFailed(e.getMessage());
            importLog.save(log);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }

    private ImportFn resolveImporter(String slug) {
        return switch (slug) {
            case "restaurants" -> restaurantCsv::parse;
            case "menus" -> menuCsv::parse;
            case "products" -> productCsv::parse;
            case "nutrition" -> nutritionCsv::parse;
            case "ingredients" -> ingredientsCsv::parse;
            case "menu-items" -> menuItemCsv::parse;
            case "photos" -> photoCsv::parse;
            case "menu-assets" -> menuAssetCsv::parse;
            default -> throw new IllegalArgumentException("Unknown CSV resource: " + slug);
        };
    }

    @FunctionalInterface
    private interface ImportFn {
        CsvImportReport parse(Reader reader, boolean dryRun) throws IOException;
    }

    @FunctionalInterface
    private interface CsvImporter {
        CsvImportReport run() throws IOException;
    }

    // ------------------------------------------------------------------ helpers

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static LocalDate parseDateOrNull(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be ISO date (yyyy-MM-dd): " + s);
        }
    }
}
