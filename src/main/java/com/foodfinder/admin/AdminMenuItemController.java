package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.product.ProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/admin/api/menu-items")
public class AdminMenuItemController {

    private final MenuItemRepository items;
    private final MenuRepository menus;
    private final ProductRepository products;

    public AdminMenuItemController(MenuItemRepository items, MenuRepository menus, ProductRepository products) {
        this.items = items;
        this.menus = menus;
        this.products = products;
    }

    @GetMapping
    public List<MenuItemView> list() {
        return items.findAll().stream().map(mi -> MenuItemView.of(mi, menus, products)).toList();
    }

    @PostMapping
    public ResponseEntity<MenuItemView> create(@RequestBody @Valid MenuItemUpsert body) {
        Long menuId = menus.findByMenuKey(body.menuKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown menu_key: " + body.menuKey())).getId();
        Long productId = products.findByProductKey(body.productKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + body.productKey())).getId();
        Long menuRestaurantId = menus.findById(menuId).orElseThrow().getRestaurantId();
        Long productRestaurantId = products.findById(productId).orElseThrow().getRestaurantId();
        if (!menuRestaurantId.equals(productRestaurantId)) {
            throw new AdminConflictException(
                    "menu_key and product_key belong to different restaurants");
        }
        if (items.existsByMenuIdAndProductId(menuId, productId)) {
            throw new AdminConflictException(
                    "menu_item for (" + body.menuKey() + "," + body.productKey() + ") already exists");
        }
        MenuItem mi = new MenuItem();
        apply(mi, body, menuId, productId, menuRestaurantId);
        items.save(mi);
        return ResponseEntity.ok(MenuItemView.of(mi, menus, products));
    }

    @PutMapping("/{id}")
    public MenuItemView update(@PathVariable Long id, @RequestBody @Valid MenuItemUpsert body) {
        MenuItem mi = items.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Menu item not found: " + id));
        Long menuId = menus.findByMenuKey(body.menuKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown menu_key: " + body.menuKey())).getId();
        Long productId = products.findByProductKey(body.productKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown product_key: " + body.productKey())).getId();
        Long menuRestaurantId = menus.findById(menuId).orElseThrow().getRestaurantId();
        if (!menuRestaurantId.equals(mi.getRestaurantId())) {
            throw new AdminConflictException("menu_key belongs to a different restaurant than the existing link");
        }
        apply(mi, body, menuId, productId, menuRestaurantId);
        items.save(mi);
        return MenuItemView.of(mi, menus, products);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!items.existsById(id)) {
            throw new NoSuchElementException("Menu item not found: " + id);
        }
        items.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(MenuItem mi, MenuItemUpsert body, Long menuId, Long productId, Long restaurantId) {
        mi.setMenuId(menuId);
        mi.setProductId(productId);
        mi.setRestaurantId(restaurantId);
        mi.setSectionName(body.sectionName() == null || body.sectionName().isBlank() ? "Altele" : body.sectionName());
        mi.setPrice(body.price());
        mi.setCurrency(body.currency() == null || body.currency().isBlank() ? "RON" : body.currency());
        mi.setAvailable(body.available() == null ? true : body.available());
        mi.setSortOrder(body.sortOrder() == null ? 0 : body.sortOrder());
    }

    public record MenuItemUpsert(
            @NotBlank String menuKey,
            @NotBlank String productKey,
            String sectionName,
            BigDecimal price,
            String currency,
            Boolean available,
            Integer sortOrder) {
    }

    public record MenuItemView(
            Long id, String menuKey, String productKey, Long restaurantId,
            String sectionName, BigDecimal price, String currency,
            boolean available, int sortOrder) {
        static MenuItemView of(MenuItem mi, MenuRepository menus, ProductRepository products) {
            String menuKey = menus.findById(mi.getMenuId()).map(m -> m.getMenuKey()).orElse("");
            String productKey = products.findById(mi.getProductId()).map(p -> p.getProductKey()).orElse("");
            return new MenuItemView(mi.getId(), menuKey, productKey, mi.getRestaurantId(),
                    mi.getSectionName(), mi.getPrice(), mi.getCurrency(),
                    mi.isAvailable(), mi.getSortOrder());
        }
    }
}
