package com.foodfinder.admin;

import com.foodfinder.common.AdminConflictException;
import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.menu.MenuType;
import com.foodfinder.restaurant.RestaurantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/admin/api/menus")
public class AdminMenuController {

    private final MenuRepository menus;
    private final RestaurantRepository restaurants;

    public AdminMenuController(MenuRepository menus, RestaurantRepository restaurants) {
        this.menus = menus;
        this.restaurants = restaurants;
    }

    @GetMapping
    public List<MenuView> list() {
        return menus.findAll().stream().map(MenuView::of).toList();
    }

    @PostMapping
    public ResponseEntity<MenuView> create(@RequestBody @Valid MenuUpsert body,
                                          org.springframework.security.core.Authentication auth) {
        if (menus.existsByMenuKey(body.menuKey())) {
            throw new AdminConflictException("menu_key already exists: " + body.menuKey());
        }
        Long restaurantId = restaurants.findByRestaurantKey(body.restaurantKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + body.restaurantKey()))
                .getId();
        if (body.validFrom() != null && body.validTo() != null && body.validTo().isBefore(body.validFrom())) {
            throw new IllegalArgumentException("valid_to must not be before valid_from");
        }
        Menu m = new Menu();
        apply(m, body, restaurantId);
        m.setUpdatedBy(actor(auth));
        menus.save(m);
        return ResponseEntity.ok(MenuView.of(m));
    }

    @GetMapping("/{menuKey}")
    public MenuView get(@PathVariable String menuKey) {
        return MenuView.of(loadOrThrow(menuKey));
    }

    @PutMapping("/{menuKey}")
    public MenuView update(@PathVariable String menuKey, @RequestBody @Valid MenuUpsert body,
                           org.springframework.security.core.Authentication auth) {
        Menu m = loadOrThrow(menuKey);
        Long restaurantId = restaurants.findByRestaurantKey(body.restaurantKey())
                .orElseThrow(() -> new NoSuchElementException("Unknown restaurant_key: " + body.restaurantKey()))
                .getId();
        apply(m, body, restaurantId);
        m.setUpdatedBy(actor(auth));
        menus.save(m);
        return MenuView.of(m);
    }

    @PatchMapping("/{menuKey}/status")
    public MenuView updateStatus(@PathVariable String menuKey, @RequestBody MenuStatusUpdate body,
                                 org.springframework.security.core.Authentication auth) {
        Menu m = loadOrThrow(menuKey);
        m.setStatus(body.status());
        if (body.status() == MenuStatus.PUBLISHED && m.getPublishedAt() == null) {
            m.setPublishedAt(Instant.now());
        }
        m.setUpdatedBy(actor(auth));
        menus.save(m);
        return MenuView.of(m);
    }

    private static String actor(org.springframework.security.core.Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private Menu loadOrThrow(String key) {
        return menus.findByMenuKey(key)
                .orElseThrow(() -> new NoSuchElementException("Menu not found: " + key));
    }

    private void apply(Menu m, MenuUpsert body, Long restaurantId) {
        m.setMenuKey(body.menuKey());
        m.setRestaurantId(restaurantId);
        m.setName(body.name());
        m.setMenuType(body.menuType() == null ? MenuType.PERMANENT : body.menuType());
        m.setStatus(body.status() == null ? MenuStatus.DRAFT : body.status());
        m.setSourceUrl(body.sourceUrl());
        m.setValidFrom(body.validFrom());
        m.setValidTo(body.validTo());
    }

    public record MenuUpsert(
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String menuKey,
            @NotBlank String restaurantKey,
            @NotBlank String name,
            MenuType menuType,
            MenuStatus status,
            String sourceUrl,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    public record MenuStatusUpdate(MenuStatus status) {
    }

    public record MenuView(
            Long id,
            String menuKey,
            Long restaurantId,
            String name,
            String menuType,
            String status,
            String sourceUrl,
            LocalDate validFrom,
            LocalDate validTo,
            Instant publishedAt) {
        static MenuView of(Menu m) {
            return new MenuView(m.getId(), m.getMenuKey(), m.getRestaurantId(), m.getName(),
                    m.getMenuType() == null ? null : m.getMenuType().name(),
                    m.getStatus() == null ? null : m.getStatus().name(),
                    m.getSourceUrl(), m.getValidFrom(), m.getValidTo(), m.getPublishedAt());
        }
    }
}
