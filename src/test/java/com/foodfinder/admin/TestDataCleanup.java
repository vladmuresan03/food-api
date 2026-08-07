package com.foodfinder.admin;

import com.foodfinder.menu.MenuAssetRepository;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cleanup helper for tests that need to commit deletes OUTSIDE the
 * test's outer {@code @Transactional}. The test class's transaction
 * is rolled back at the end of each test, which also rolls back any
 * {@code @AfterEach} deletes — so they do nothing. This service uses
 * {@code REQUIRES_NEW} so the deletes run in a fresh, independent
 * transaction that actually commits.
 *
 * <p>Used by tests that exercise REQUIRES_NEW production code paths
 * (e.g. {@link com.foodfinder.csv.CsvController}, {@link BundleImporter})
 * whose writes the test's outer transaction cannot see or roll back.
 */
@Service
public class TestDataCleanup {

    @Autowired private RestaurantRepository restaurants;
    @Autowired private MenuRepository menus;
    @Autowired private MenuItemRepository menuItems;
    @Autowired private MenuAssetRepository menuAssets;
    @Autowired private ProductRepository products;
    @Autowired private PhotoRepository photos;
    @Autowired private CsvImportLogRepository importLog;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void wipeCatalogData() {
        // Order matters: children before parents (FK chain).
        menuItems.deleteAllInBatch();
        menuAssets.deleteAllInBatch();
        photos.deleteAllInBatch();
        products.deleteAllInBatch();
        menus.deleteAllInBatch();
        restaurants.deleteAllInBatch();
        importLog.deleteAllInBatch();
    }
}
