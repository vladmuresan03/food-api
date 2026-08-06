package com.foodfinder.photo;

import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit-level tests for {@link PhotoStorageService.update} that exercise
 * the primary-photo scope-change invariant. The B6 regression: clearing
 * productKey on a primary photo for product X must demote any existing
 * restaurant-level primary, otherwise the unique partial index
 * ux_photo_primary_per_restaurant fires and the update 500s.
 */
@SpringBootTest
@Transactional
class PhotoStorageServiceTest {

    @Autowired RestaurantRepository restaurants;
    @Autowired ProductRepository products;
    @Autowired PhotoRepository photos;
    @Autowired PhotoStorageService photoService;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                b6-r,B6 R,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                b6-p,b6-r,B6 P,ACTIVE
                """), false);
    }

    @Test
    void clearingProductKeyOnPrimaryPhotoDemotesRestaurantPrimary() {
        Long restaurantId = restaurants.findByRestaurantKey("b6-r").orElseThrow().getId();
        Long productId = products.findByProductKey("b6-p").orElseThrow().getId();

        Photo restaurantPrimary = new Photo();
        restaurantPrimary.setPhotoKey("b6-restaurant-photo");
        restaurantPrimary.setRestaurantId(restaurantId);
        restaurantPrimary.setProductId(null);
        restaurantPrimary.setSourceType(PhotoSourceType.UPLOAD);
        restaurantPrimary.setStorageKey("storage/b6-restaurant.jpg");
        restaurantPrimary.setPrimaryPhoto(true);
        restaurantPrimary.setStatus(PhotoStatus.ACTIVE);
        photos.save(restaurantPrimary);

        Photo productPrimary = new Photo();
        productPrimary.setPhotoKey("b6-product-photo");
        productPrimary.setRestaurantId(restaurantId);
        productPrimary.setProductId(productId);
        productPrimary.setSourceType(PhotoSourceType.UPLOAD);
        productPrimary.setStorageKey("storage/b6-product.jpg");
        productPrimary.setPrimaryPhoto(true);
        productPrimary.setStatus(PhotoStatus.ACTIVE);
        photos.save(productPrimary);

        // Clearing productKey on the product-primary must demote the
        // existing restaurant-level primary; otherwise both photos would
        // be primary at restaurant scope and the unique index 500s.
        photoService.update("b6-product-photo", "", null, null, null);

        Photo reloadedProductPhoto = photos.findByPhotoKey("b6-product-photo").orElseThrow();
        Photo reloadedRestaurantPhoto = photos.findByPhotoKey("b6-restaurant-photo").orElseThrow();
        assertThat(reloadedProductPhoto.getProductId()).isNull();
        assertThat(reloadedProductPhoto.isPrimaryPhoto()).isTrue();
        assertThat(reloadedRestaurantPhoto.isPrimaryPhoto()).isFalse();
    }
}
