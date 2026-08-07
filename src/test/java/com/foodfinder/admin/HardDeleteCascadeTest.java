package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.product.ProductIngredientRepository;
import com.foodfinder.product.ProductNutritionRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.csv.IngredientsCsv;
import com.foodfinder.csv.MenuAssetCsv;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.NutritionCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the V8 ON DELETE CASCADE wiring for restaurant, menu, and
 * product hard-delete paths. Both the REST endpoint and the form POST
 * are exercised; the in-memory setup mimics a real catalog with
 * photos, menu items, menu assets, plus the Tier 1A nutrition +
 * ingredients overlays.
 */
@IntegrationTest
@AutoConfigureMockMvc
class HardDeleteCascadeTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;
    @Autowired NutritionCsv nutritionCsv;
    @Autowired IngredientsCsv ingredientsCsv;
    @Autowired MenuAssetCsv menuAssetCsv;
    @Autowired RestaurantRepository restaurants;
    @Autowired MenuRepository menus;
    @Autowired ProductRepository products;
    @Autowired MenuItemRepository menuItems;
    @Autowired PhotoRepository photos;
    @Autowired ProductNutritionRepository nutritions;
    @Autowired ProductIngredientRepository ingredients;
    @Autowired TestDataCleanup cleanup;

    @BeforeEach
    void seed() throws Exception {
        cleanup.wipeCatalogData();
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                hd-r,HD R,Cluj-Napoca,ACTIVE
                """), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                hd-m,hd-r,HD M,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                hd-p,hd-r,HD P,ACTIVE
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                hd-m,hd-p,Starters,10.00,RON,true,0
                """), false);
        nutritionCsv.parse(new StringReader("""
                product_key,energy_kcal
                hd-p,250.00
                """), false);
        ingredientsCsv.parse(new StringReader("""
                product_key,position,name
                hd-p,1,Flour
                hd-p,2,Eggs
                """), false);
        menuAssetCsv.parse(new StringReader("""
                asset_key,menu_id,asset_type,storage_key,sort_order
                hd-asset,1,PDF,/tmp/menu.pdf,0
                """), false);
    }

    @AfterEach
    void wipe() {
        cleanup.wipeCatalogData();
    }

    // ------------------------------------------------------------------ restaurant hard-delete

    @Test
    void deleteRestaurantRestDeletesIt() throws Exception {
        mvc.perform(delete("/admin/api/restaurants/hd-r")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(restaurants.findByRestaurantKey("hd-r")).isEmpty();
    }

    @Test
    void deleteRestaurantCascadesToMenusProductsMenuItemsPhotosAndOverlays() throws Exception {
        // Sanity check the seed is present
        Long menuId = menus.findByMenuKey("hd-m").orElseThrow().getId();
        Long productId = products.findByProductKey("hd-p").orElseThrow().getId();
        assertThat(menuItems.findByMenuIdOrderBySortOrderAsc(menuId)).hasSize(1);
        assertThat(nutritions.findById(productId)).isPresent();
        assertThat(ingredients.findByIdProductIdOrderByIdPositionAsc(productId)).hasSize(2);

        mvc.perform(delete("/admin/api/restaurants/hd-r")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // After the cascade, every child row should be gone.
        assertThat(restaurants.findByRestaurantKey("hd-r")).isEmpty();
        assertThat(menus.findByMenuKey("hd-m")).isEmpty();
        assertThat(products.findByProductKey("hd-p")).isEmpty();
        assertThat(menuItems.findByMenuIdOrderBySortOrderAsc(menuId)).isEmpty();
        assertThat(nutritions.findById(productId)).isEmpty();
        assertThat(ingredients.findByIdProductIdOrderByIdPositionAsc(productId)).isEmpty();
    }

    @Test
    void deleteRestaurantFormPostHardDelete() throws Exception {
        mvc.perform(post("/admin/restaurants/hd-r/hard-delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/restaurants"));

        assertThat(restaurants.findByRestaurantKey("hd-r")).isEmpty();
        assertThat(menus.findByMenuKey("hd-m")).isEmpty();
        assertThat(products.findByProductKey("hd-p")).isEmpty();
    }

    @Test
    void deleteUnknownRestaurantRestReturns404() throws Exception {
        mvc.perform(delete("/admin/api/restaurants/no-such-r")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ menu hard-delete

    @Test
    void deleteMenuRestDeletesItAndItsItems() throws Exception {
        Long menuId = menus.findByMenuKey("hd-m").orElseThrow().getId();
        assertThat(menuItems.findByMenuIdOrderBySortOrderAsc(menuId)).hasSize(1);

        mvc.perform(delete("/admin/api/menus/hd-m")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(menus.findByMenuKey("hd-m")).isEmpty();
        assertThat(menuItems.findByMenuIdOrderBySortOrderAsc(menuId)).isEmpty();
        // The restaurant and product are NOT touched by menu delete.
        assertThat(restaurants.findByRestaurantKey("hd-r")).isPresent();
        assertThat(products.findByProductKey("hd-p")).isPresent();
    }

    @Test
    void deleteMenuFormPostHardDelete() throws Exception {
        mvc.perform(post("/admin/menus/hd-m/hard-delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menus"));

        assertThat(menus.findByMenuKey("hd-m")).isEmpty();
    }

    // ------------------------------------------------------------------ product hard-delete

    @Test
    void deleteProductRestDeletesItAndItsOverlays() throws Exception {
        Long productId = products.findByProductKey("hd-p").orElseThrow().getId();
        assertThat(nutritions.findById(productId)).isPresent();
        assertThat(ingredients.findByIdProductIdOrderByIdPositionAsc(productId)).hasSize(2);

        mvc.perform(delete("/admin/api/products/hd-p")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(products.findByProductKey("hd-p")).isEmpty();
        assertThat(nutritions.findById(productId)).isEmpty();
        assertThat(ingredients.findByIdProductIdOrderByIdPositionAsc(productId)).isEmpty();
        // The menu_items that referenced this product are also gone
        // (composite FK to product cascades), but the menu survives.
        Long menuId = menus.findByMenuKey("hd-m").orElseThrow().getId();
        assertThat(menuItems.findByMenuIdOrderBySortOrderAsc(menuId)).isEmpty();
        assertThat(menus.findByMenuKey("hd-m")).isPresent();
    }

    @Test
    void deleteProductFormPostHardDelete() throws Exception {
        mvc.perform(post("/admin/products/hd-p/hard-delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products"));

        assertThat(products.findByProductKey("hd-p")).isEmpty();
    }

    @Test
    void softArchiveStillWorksAfterCascadeAdded() throws Exception {
        // The archive buttons must still work and not be broken by the
        // new FK CASCADE behavior. Archive flips status, not delete.
        mvc.perform(post("/admin/restaurants/hd-r/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(restaurants.findByRestaurantKey("hd-r").orElseThrow().getStatus().name())
                .isEqualTo("ARCHIVED");
        // Children survive an archive (archive != delete).
        assertThat(menus.findByMenuKey("hd-m")).isPresent();
        assertThat(products.findByProductKey("hd-p")).isPresent();
    }
}
