package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the archive / activate / hard-delete lifecycle for menu items.
 *
 * <p>Menu items don't carry a {@code status} field — they expose a
 * boolean {@code available} instead, which the public API honours.
 * The two-step guard here is "Hide then Delete" rather than "Archive
 * then Delete", but the server-side precondition is the same shape:
 * the operator must Hide the item before the hard delete is allowed.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
class MenuItemLifecycleTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;
    @Autowired MenuItemRepository menuItems;
    @Autowired ProductRepository products;
    @Autowired TestDataCleanup cleanup;

    private static final String REST = "mi-r";
    private static final String MENU = "mi-m";
    private static final String PROD = "mi-p";
    private static final String PROD_2 = "mi-p-2";

    @BeforeEach
    void seed() throws Exception {
        cleanup.wipeCatalogData();
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                %s,MI R,Cluj-Napoca,ACTIVE
                """.formatted(REST)), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                %s,%s,MI M,PERMANENT,PUBLISHED
                """.formatted(MENU, REST)), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                %s,%s,MI P,ACTIVE
                %s,%s,MI P2,ACTIVE
                """.formatted(PROD, REST, PROD_2, REST)), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                %s,%s,Starters,10.00,RON,true,0
                %s,%s,Starters,11.00,RON,true,1
                """.formatted(MENU, PROD, MENU, PROD_2)), false);
    }

    @AfterEach
    void wipe() {
        cleanup.wipeCatalogData();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void archiveMenuItemHidesIt() throws Exception {
        long id = itemIdForProductKey(PROD);

        mvc.perform(post("/admin/menu-items/" + id + "/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("successMessage"));

        MenuItem reloaded = menuItems.findById(id).orElseThrow();
        assertThat(reloaded.isAvailable()).isFalse();
    }

    @Test
    void activateMenuItemShowsIt() throws Exception {
        long id = itemIdForProductKey(PROD);
        hideDirectly(id);

        mvc.perform(post("/admin/menu-items/" + id + "/activate")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("successMessage"));

        MenuItem reloaded = menuItems.findById(id).orElseThrow();
        assertThat(reloaded.isAvailable()).isTrue();
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    void archivingAlreadyHiddenItemIsNoOp() throws Exception {
        long id = itemIdForProductKey(PROD);
        hideDirectly(id);

        mvc.perform(post("/admin/menu-items/" + id + "/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(menuItems.findById(id)).isPresent();
        assertThat(menuItems.findById(id).orElseThrow().isAvailable()).isFalse();
    }

    @Test
    void activatingAlreadyVisibleItemIsNoOp() throws Exception {
        long id = itemIdForProductKey(PROD);
        // available is already true from the seed.

        mvc.perform(post("/admin/menu-items/" + id + "/activate")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(menuItems.findById(id).orElseThrow().isAvailable()).isTrue();
    }

    // ------------------------------------------------------------------ two-step guard

    @Test
    void hardDeleteVisibleMenuItemIsRejected() throws Exception {
        long id = itemIdForProductKey(PROD);
        // Seeded as available=true.

        mvc.perform(post("/admin/menu-items/" + id + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(menuItems.findById(id)).isPresent();
    }

    @Test
    void hardDeleteHiddenMenuItemSucceeds() throws Exception {
        long id = itemIdForProductKey(PROD);
        hideDirectly(id);

        mvc.perform(post("/admin/menu-items/" + id + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(menuItems.findById(id)).isEmpty();
    }

    @Test
    void fullLifecycleArchiveThenDeleteWorks() throws Exception {
        long id = itemIdForProductKey(PROD_2);

        // Step 1: archive (Hide).
        mvc.perform(post("/admin/menu-items/" + id + "/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(menuItems.findById(id).orElseThrow().isAvailable()).isFalse();

        // Step 2: hard delete is now allowed.
        mvc.perform(post("/admin/menu-items/" + id + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(menuItems.findById(id)).isEmpty();
    }

    // ------------------------------------------------------------------ 404

    @Test
    void archiveUnknownMenuItemReturns404() throws Exception {
        mvc.perform(post("/admin/menu-items/999999/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    /** Resolve the menu_item id by looking up the product's id, then matching the join row. */
    private long itemIdForProductKey(String productKey) {
        Product p = products.findByProductKey(productKey)
                .orElseThrow(() -> new IllegalStateException("No product with key " + productKey));
        return menuItems.findByProductId(p.getId()).stream()
                .findFirst()
                .map(MenuItem::getId)
                .orElseThrow(() -> new IllegalStateException("No menu_item for product " + productKey));
    }

    private void hideDirectly(long menuItemId) {
        MenuItem mi = menuItems.findById(menuItemId).orElseThrow();
        mi.setAvailable(false);
        menuItems.save(mi);
    }
}
