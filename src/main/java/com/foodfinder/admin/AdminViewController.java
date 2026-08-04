package com.foodfinder.admin;

import com.foodfinder.csv.CsvImportReport;
import com.foodfinder.csv.MenuAssetCsv;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.PhotoCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
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
import java.nio.charset.StandardCharsets;

/**
 * Server-rendered admin pages. Form-login + Basic auth (configured in
 * SecurityConfig) gates everything under /admin/**.
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

    public AdminViewController(AdminViewService views, RestaurantCsv restaurantCsv, MenuCsv menuCsv,
                               ProductCsv productCsv, MenuItemCsv menuItemCsv, PhotoCsv photoCsv,
                               MenuAssetCsv menuAssetCsv) {
        this.views = views;
        this.restaurantCsv = restaurantCsv;
        this.menuCsv = menuCsv;
        this.productCsv = productCsv;
        this.menuItemCsv = menuItemCsv;
        this.photoCsv = photoCsv;
        this.menuAssetCsv = menuAssetCsv;
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

    // ------------------------------------------------------------------ CSV import submission

    @PostMapping("/csv/{slug}")
    public String importCsv(@PathVariable String slug,
                            @RequestParam("file") MultipartFile file,
                            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                            Model model) throws IOException {
        CsvImportReport report = runImport(slug, file, dryRun);
        model.addAttribute("report", report);
        model.addAttribute("resources", views.csvResources());
        return "admin/csv";
    }

    private CsvImportReport runImport(String slug, MultipartFile file, boolean dryRun) throws IOException {
        ImportFn fn = switch (slug) {
            case "restaurants" -> restaurantCsv::parse;
            case "menus" -> menuCsv::parse;
            case "products" -> productCsv::parse;
            case "menu-items" -> menuItemCsv::parse;
            case "photos" -> photoCsv::parse;
            case "menu-assets" -> menuAssetCsv::parse;
            default -> throw new IllegalArgumentException("Unknown CSV resource: " + slug);
        };
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return fn.parse(reader, dryRun);
        }
    }

    @FunctionalInterface
    private interface ImportFn {
        CsvImportReport parse(Reader reader, boolean dryRun) throws IOException;
    }
}
