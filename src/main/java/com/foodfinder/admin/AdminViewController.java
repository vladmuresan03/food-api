package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.csv.CsvImportReport;
import com.foodfinder.csv.MenuAssetCsv;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.PhotoCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.menu.MenuType;
import com.foodfinder.product.Product;
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
import java.util.NoSuchElementException;

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
    private final MenuItemCsv menuItemCsv;
    private final PhotoCsv photoCsv;
    private final MenuAssetCsv menuAssetCsv;
    private final RestaurantRepository restaurants;
    private final MenuRepository menus;
    private final ProductRepository products;
    private final MenuItemRepository menuItems;
    private final CsvImportLogRepository importLog;
    private final BundleImporter bundleImporter;
    private final CsvPreviewService previewService;

    public AdminViewController(AdminViewService views, RestaurantCsv restaurantCsv, MenuCsv menuCsv,
                               ProductCsv productCsv, MenuItemCsv menuItemCsv, PhotoCsv photoCsv,
                               MenuAssetCsv menuAssetCsv, RestaurantRepository restaurants,
                               MenuRepository menus, ProductRepository products,
                               MenuItemRepository menuItems, CsvImportLogRepository importLog,
                               BundleImporter bundleImporter, CsvPreviewService previewService) {
        this.views = views;
        this.restaurantCsv = restaurantCsv;
        this.menuCsv = menuCsv;
        this.productCsv = productCsv;
        this.menuItemCsv = menuItemCsv;
        this.photoCsv = photoCsv;
        this.menuAssetCsv = menuAssetCsv;
        this.restaurants = restaurants;
        this.menus = menus;
        this.products = products;
        this.menuItems = menuItems;
        this.importLog = importLog;
        this.bundleImporter = bundleImporter;
        this.previewService = previewService;
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
                                @RequestParam(required = false) ProductStatus status,
                                Authentication auth,
                                RedirectAttributes ra) {
        if (products.existsByProductKey(productKey)) {
            throw new AdminConflictException("product_key already exists: " + productKey);
        }
        Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                .getId();
        Product p = new Product();
        applyProduct(p, productKey, restaurantId, name, description, weightText, status);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        ra.addFlashAttribute("successMessage", "Product '" + p.getProductKey() + "' created");
        return "redirect:/admin/products";
    }

    @PostMapping("/products/{key}")
    public String updateProduct(@PathVariable("key") String key,
                                @RequestParam String restaurantKey,
                                @RequestParam String name,
                                @RequestParam(required = false) String description,
                                @RequestParam(required = false) String weightText,
                                @RequestParam(required = false) ProductStatus status,
                                Authentication auth,
                                RedirectAttributes ra) {
        Product p = products.findByProductKey(key)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + key));
        Long restaurantId = restaurants.findByRestaurantKey(restaurantKey)
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + restaurantKey))
                .getId();
        applyProduct(p, key, restaurantId, name, description, weightText, status);
        p.setUpdatedBy(actor(auth));
        products.save(p);
        ra.addFlashAttribute("successMessage", "Product '" + p.getProductKey() + "' updated");
        return "redirect:/admin/products";
    }

    private void applyProduct(Product p, String productKey, Long restaurantId, String name,
                              String description, String weightText, ProductStatus status) {
        if (productKey == null || productKey.isBlank()) {
            throw new IllegalArgumentException("product_key is required");
        }
        if (!productKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("product_key must be lowercase slug: " + productKey);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        p.setProductKey(productKey);
        p.setRestaurantId(restaurantId);
        p.setName(name);
        p.setDescription(blankToNull(description));
        p.setWeightText(blankToNull(weightText));
        p.setStatus(status == null ? ProductStatus.DRAFT : status);
    }

    // ------------------------------------------------------------------ menu-item CRUD

    @GetMapping("/menu-items/{id}/edit")
    public String editMenuItem(@PathVariable("id") Long id, Model model) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        model.addAttribute("mi", mi);
        return "admin/menu-item-form";
    }

    @PostMapping("/menu-items/{id}")
    public String updateMenuItem(@PathVariable("id") Long id,
                                 @RequestParam String sectionName,
                                 @RequestParam(required = false) BigDecimal price,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(required = false) Boolean available,
                                 @RequestParam(required = false) Integer sortOrder,
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
        mi.setSectionName(sectionName);
        mi.setPrice(price);
        mi.setCurrency(currency == null || currency.isBlank() ? "RON" : currency.toUpperCase());
        mi.setAvailable(available == null ? mi.isAvailable() : available);
        mi.setSortOrder(sortOrder == null ? mi.getSortOrder() : sortOrder);
        mi.setUpdatedBy(actor(auth));
        menuItems.save(mi);
        ra.addFlashAttribute("successMessage", "Menu item updated");
        return "redirect:/admin/menu-items";
    }

    @PostMapping("/menu-items/{id}/delete")
    public String deleteMenuItem(@PathVariable("id") Long id, RedirectAttributes ra) {
        MenuItem mi = menuItems.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        menuItems.delete(mi);
        ra.addFlashAttribute("successMessage", "Menu item deleted");
        return "redirect:/admin/menu-items";
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
