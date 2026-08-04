package com.foodfinder.publicapi;

import com.foodfinder.restaurant.RestaurantStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PublicController {

    private final PublicApiService service;

    public PublicController(PublicApiService service) {
        this.service = service;
    }

    @GetMapping(value = "/restaurants", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Dtos.RestaurantSummary> listRestaurants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RestaurantStatus parsed = (status == null || status.isBlank())
                ? null : RestaurantStatus.valueOf(status.toUpperCase());
        return service.listRestaurants(q, city, parsed, page, size);
    }

    @GetMapping(value = "/restaurants/{restaurantKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Dtos.RestaurantDetail restaurantDetail(@PathVariable String restaurantKey) {
        return service.restaurantDetail(restaurantKey);
    }

    @GetMapping(value = "/restaurants/{restaurantKey}/menus", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Dtos.MenuSummary> restaurantMenus(@PathVariable String restaurantKey) {
        return service.listRestaurantMenus(restaurantKey);
    }

    @GetMapping(value = "/menus/{menuKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Dtos.MenuDetail menuDetail(@PathVariable String menuKey) {
        return service.menuDetail(menuKey);
    }

    @GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Dtos.ProductSummary> listProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String restaurantKey,
            @RequestParam(required = false) String menuKey,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean hasPhoto,
            @RequestParam(required = false) Boolean available,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listProducts(q, restaurantKey, menuKey, section,
                minPrice, maxPrice, hasPhoto, available, page, size);
    }

    @GetMapping(value = "/products/{productKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Dtos.ProductDetail productDetail(@PathVariable String productKey) {
        return service.productDetail(productKey);
    }
}
