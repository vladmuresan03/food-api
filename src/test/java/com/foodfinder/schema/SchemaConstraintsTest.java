package com.foodfinder.schema;

import com.foodfinder.menu.MenuAssetRepository;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the Flyway schema applies to PostgreSQL and that every
 * constraint is enforced at the database level (inserts bypass JPA on
 * purpose, going straight through JDBC).
 */
@SpringBootTest
@Transactional
class SchemaConstraintsTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RestaurantRepository restaurants;
    @Autowired
    MenuRepository menus;
    @Autowired
    ProductRepository products;
    @Autowired
    MenuItemRepository menuItems;
    @Autowired
    PhotoRepository photos;
    @Autowired
    MenuAssetRepository menuAssets;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long insertRestaurant(String key, String name) {
        jdbc.update(
                "INSERT INTO restaurant (restaurant_key, name, status) VALUES (?, ?, 'ACTIVE')",
                key, name);
        return jdbc.queryForObject("SELECT id FROM restaurant WHERE restaurant_key = ?",
                Long.class, key);
    }

    private long insertMenu(String key, long restaurantId) {
        jdbc.update(
                "INSERT INTO menu (menu_key, restaurant_id, name) VALUES (?, ?, 'Main')",
                key, restaurantId);
        return jdbc.queryForObject("SELECT id FROM menu WHERE menu_key = ?", Long.class, key);
    }

    private long insertProduct(String key, long restaurantId) {
        jdbc.update(
                "INSERT INTO product (product_key, restaurant_id, name) VALUES (?, ?, 'Dish')",
                key, restaurantId);
        return jdbc.queryForObject("SELECT id FROM product WHERE product_key = ?", Long.class, key);
    }

    private void insertMenuItem(long menuId, long productId, long restaurantId) {
        jdbc.update(
                "INSERT INTO menu_item (menu_id, product_id, restaurant_id) VALUES (?, ?, ?)",
                menuId, productId, restaurantId);
    }

    // ------------------------------------------------------------------
    // migration + context
    // ------------------------------------------------------------------

    @Test
    void migrationAppliesAndRepositoriesAreWired() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(restaurants).isNotNull();
        assertThat(menus).isNotNull();
        assertThat(products).isNotNull();
        assertThat(menuItems).isNotNull();
        assertThat(photos).isNotNull();
        assertThat(menuAssets).isNotNull();
    }

    // ------------------------------------------------------------------
    // restaurant
    // ------------------------------------------------------------------

    @Test
    void invalidCoordinatePairFails() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO restaurant (restaurant_key, name, latitude) VALUES ('geo-broken', 'X', 46.77)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void latitudeOutOfRangeFails() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO restaurant (restaurant_key, name, latitude, longitude) "
                        + "VALUES ('geo-range', 'X', 91.0, 23.5)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidRestaurantStatusFails() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO restaurant (restaurant_key, name, status) VALUES ('bad-status', 'X', 'BOGUS')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonSlugRestaurantKeyFails() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO restaurant (restaurant_key, name) VALUES ('Bad_Key', 'X')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateRestaurantKeyFails() {
        insertRestaurant("dup-key", "One");
        assertThatThrownBy(() -> insertRestaurant("dup-key", "Two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // menu
    // ------------------------------------------------------------------

    @Test
    void menuValidToBeforeValidFromFails() {
        long r = insertRestaurant("menu-validity", "X");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu (menu_key, restaurant_id, name, valid_from, valid_to) "
                        + "VALUES ('bad-validity', ?, 'M', '2026-08-10', '2026-08-01')", r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidMenuTypeFails() {
        long r = insertRestaurant("menu-type", "X");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu (menu_key, restaurant_id, name, menu_type) "
                        + "VALUES ('bad-type', ?, 'M', 'BRUNCH')", r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // menu_item
    // ------------------------------------------------------------------

    @Test
    void crossRestaurantMenuItemFails() {
        long r1 = insertRestaurant("rest-a", "A");
        long r2 = insertRestaurant("rest-b", "B");
        long menuOfA = insertMenu("menu-of-a", r1);
        long productOfB = insertProduct("prod-of-b", r2);

        assertThatThrownBy(() -> insertMenuItem(menuOfA, productOfB, r2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateMenuItemFails() {
        long r = insertRestaurant("rest-dup", "X");
        long m = insertMenu("menu-dup", r);
        long p = insertProduct("prod-dup", r);
        insertMenuItem(m, p, r);

        assertThatThrownBy(() -> insertMenuItem(m, p, r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativePriceFails() {
        long r = insertRestaurant("rest-price", "X");
        long m = insertMenu("menu-price", r);
        long p = insertProduct("prod-price", r);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu_item (menu_id, product_id, restaurant_id, price) "
                        + "VALUES (?, ?, ?, -1)", m, p, r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lowercaseCurrencyFails() {
        long r = insertRestaurant("rest-currency", "X");
        long m = insertMenu("menu-currency", r);
        long p = insertProduct("prod-currency", r);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu_item (menu_id, product_id, restaurant_id, currency) "
                        + "VALUES (?, ?, ?, 'ron')", m, p, r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // photo
    // ------------------------------------------------------------------

    private void insertPhoto(String key, long restaurantId, Long productId,
                             String storageKey, String externalUrl, boolean primary) {
        jdbc.update(
                "INSERT INTO photo (photo_key, restaurant_id, product_id, source_type, "
                        + "storage_key, external_url, is_primary) "
                        + "VALUES (?, ?, ?, 'UPLOAD', ?, ?, ?)",
                key, restaurantId, productId, storageKey, externalUrl, primary);
    }

    @Test
    void photoWithoutStorageOrUrlFails() {
        long r = insertRestaurant("rest-photo", "X");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO photo (photo_key, restaurant_id, source_type) "
                        + "VALUES ('no-source', ?, 'UPLOAD')", r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void photoWithBothStorageAndUrlFails() {
        long r = insertRestaurant("rest-photo2", "X");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO photo (photo_key, restaurant_id, source_type, storage_key, external_url) "
                        + "VALUES ('both-sources', ?, 'UPLOAD', 'photos/a.jpg', 'https://x/y.jpg')", r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void photoLinkedToForeignRestaurantProductFails() {
        long r1 = insertRestaurant("rest-photo3", "X");
        long r2 = insertRestaurant("rest-photo4", "Y");
        long productOfR2 = insertProduct("prod-foreign", r2);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO photo (photo_key, restaurant_id, product_id, source_type, external_url) "
                        + "VALUES ('foreign-product', ?, ?, 'UPLOAD', 'https://x/y.jpg')",
                r1, productOfR2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOnePrimaryPhotoPerProduct() {
        long r = insertRestaurant("rest-primary", "X");
        long p = insertProduct("prod-primary", r);
        insertPhoto("primary-one", r, p, "photos/a.jpg", null, true);

        assertThatThrownBy(() -> insertPhoto("primary-two", r, p, "photos/b.jpg", null, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneRestaurantLevelPrimaryPhoto() {
        long r = insertRestaurant("rest-rprimary", "X");
        insertPhoto("rprimary-one", r, null, "photos/a.jpg", null, true);

        assertThatThrownBy(() -> insertPhoto("rprimary-two", r, null, "photos/b.jpg", null, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // menu_asset
    // ------------------------------------------------------------------

    @Test
    void assetStorageXorFails() {
        long r = insertRestaurant("rest-asset", "X");
        long m = insertMenu("menu-asset", r);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu_asset (asset_key, menu_id, asset_type) VALUES ('asset-none', ?, 'PDF')", m))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assetInvalidSha256Fails() {
        long r = insertRestaurant("rest-asset2", "X");
        long m = insertMenu("menu-asset2", r);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu_asset (asset_key, menu_id, asset_type, source_url, sha256) "
                        + "VALUES ('asset-badsha', ?, 'URL', 'https://x/menu.pdf', 'NOT-A-SHA')", m))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assetNegativeSizeFails() {
        long r = insertRestaurant("rest-asset3", "X");
        long m = insertMenu("menu-asset3", r);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menu_asset (asset_key, menu_id, asset_type, source_url, size_bytes) "
                        + "VALUES ('asset-negsize', ?, 'URL', 'https://x/menu.pdf', -5)", m))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
