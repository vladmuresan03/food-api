package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.Menu;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.menu.MenuStatus;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.product.ProductStatus;
import com.foodfinder.restaurant.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

/**
 * HTML form-based CRUD for menus, products, and menu-items.
 * Restaurants live in {@link AdminViewTest} (their own history of
 * "list page renders rows" tests).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AdminCrudTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;
    @Autowired RestaurantRepository restaurants;
    @Autowired MenuRepository menus;
    @Autowired ProductRepository products;
    @Autowired MenuItemRepository menuItems;

    // ------------------------------------------------------------------ fixtures

    private static final String FIXTURE = """
            restaurant_key,name,city,status
            crud-r,CRUD,Cluj-Napoca,ACTIVE
            """;

    private void seedRestaurant() throws java.io.IOException {
        restaurantCsv.parse(new StringReader(FIXTURE), false);
    }

    // ------------------------------------------------------------------ menu CRUD

    @Test
    void newMenuFormRenders() throws Exception {
        seedRestaurant();
        mvc.perform(get("/admin/menus/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='menuKey']").exists())
                .andExpect(xpath("//select[@id='restaurantKey']").exists())
                .andExpect(xpath("//input[@id='name']").exists());
    }

    @Test
    void createMenuHappyPath() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/menus")
                        .param("menuKey", "main-menu")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Main")
                        .param("menuType", "PERMANENT")
                        .param("status", "DRAFT")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menus"))
                .andExpect(flash().attributeExists("successMessage"));

        Menu m = menus.findByMenuKey("main-menu").orElseThrow();
        assertThat(m.getName()).isEqualTo("Main");
        assertThat(m.getStatus()).isEqualTo(MenuStatus.DRAFT);
    }

    @Test
    void createMenuWithUnknownRestaurantReRendersForm() throws Exception {
        mvc.perform(post("/admin/menus")
                        .param("menuKey", "orphan-menu")
                        .param("restaurantKey", "nope-r")
                        .param("name", "Orphan")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unknown restaurant_key")));
    }

    @Test
    void createMenuWithInvalidDateShowsError() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/menus")
                        .param("menuKey", "bad-date")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Bad")
                        .param("validFrom", "not-a-date")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("valid_from")));
    }

    @Test
    void editMenuPrePopulatesFields() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                edit-menu,crud-r,Edit Me,PERMANENT,DRAFT
                """), false);

        mvc.perform(get("/admin/menus/edit-menu/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='menuKey'][@readonly]").exists())
                .andExpect(xpath("//input[@id='name'][@value='Edit Me']").exists());
    }

    @Test
    void updateMenuFlipsStatusAndStampsPublishedAt() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                pub-menu,crud-r,Pub,PERMANENT,DRAFT
                """), false);

        mvc.perform(post("/admin/menus/pub-menu")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Pub")
                        .param("menuType", "PERMANENT")
                        .param("status", "PUBLISHED")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menus"));

        Menu m = menus.findByMenuKey("pub-menu").orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MenuStatus.PUBLISHED);
        assertThat(m.getPublishedAt()).isNotNull();
    }

    @Test
    void archiveMenuFlipsStatus() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                arc-menu,crud-r,Arc,PERMANENT,PUBLISHED
                """), false);

        mvc.perform(post("/admin/menus/arc-menu/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menus"));

        assertThat(menus.findByMenuKey("arc-menu").orElseThrow().getStatus())
                .isEqualTo(MenuStatus.ARCHIVED);
    }

    // ------------------------------------------------------------------ product CRUD

    @Test
    void newProductFormRenders() throws Exception {
        seedRestaurant();
        mvc.perform(get("/admin/products/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='productKey']").exists())
                .andExpect(xpath("//textarea[@id='description']").exists());
    }

    @Test
    void createProductHappyPath() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/products")
                        .param("productKey", "prod-1")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Product 1")
                        .param("weightText", "350g")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products"))
                .andExpect(flash().attributeExists("successMessage"));

        Product p = products.findByProductKey("prod-1").orElseThrow();
        assertThat(p.getName()).isEqualTo("Product 1");
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.getWeightText()).isEqualTo("350g");
    }

    @Test
    void editProductPrePopulatesFields() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_text,status
                edit-prod,crud-r,Edit Me,250g,DRAFT
                """), false);

        mvc.perform(get("/admin/products/edit-prod/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='productKey'][@readonly]").exists())
                .andExpect(xpath("//input[@id='name'][@value='Edit Me']").exists());
    }

    @Test
    void updateProductChangesFields() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                upd-prod,crud-r,Original,DRAFT
                """), false);

        mvc.perform(post("/admin/products/upd-prod")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Updated")
                        .param("weightText", "500g")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Product p = products.findByProductKey("upd-prod").orElseThrow();
        assertThat(p.getName()).isEqualTo("Updated");
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.getWeightText()).isEqualTo("500g");
    }

    @Test
    void archiveProductFlipsStatus() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                arc-prod,crud-r,Arc,ACTIVE
                """), false);

        mvc.perform(post("/admin/products/arc-prod/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products"));

        assertThat(products.findByProductKey("arc-prod").orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ARCHIVED);
    }

    // ------------------------------------------------------------------ menu-item create (form)

    @Test
    void newMenuItemFormRendersWithDropdowns() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                ni-menu,crud-r,NI Menu,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                ni-prod,crud-r,NI Prod,ACTIVE
                """), false);

        mvc.perform(get("/admin/menu-items/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//select[@id='menuKey']").exists())
                .andExpect(xpath("//select[@id='productKey']").exists())
                .andExpect(xpath("//option[@value='ni-menu']").exists())
                .andExpect(xpath("//option[@value='ni-prod']").exists());
    }

    @Test
    void createMenuItemHappyPath() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                cmi-menu,crud-r,CMI,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                cmi-prod,crud-r,CMI Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "cmi-menu")
                        .param("productKey", "cmi-prod")
                        .param("sectionName", "Starters")
                        .param("price", "15.00")
                        .param("currency", "RON")
                        .param("available", "true")
                        .param("sortOrder", "3")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("successMessage"));

        Long menuId = menus.findByMenuKey("cmi-menu").orElseThrow().getId();
        Long productId = products.findByProductKey("cmi-prod").orElseThrow().getId();
        MenuItem mi = menuItems.findByMenuIdAndProductId(menuId, productId).orElseThrow();
        assertThat(mi.getSectionName()).isEqualTo("Starters");
        assertThat(mi.getPrice()).isEqualByComparingTo("15.00");
        assertThat(mi.isAvailable()).isTrue();
        assertThat(mi.getSortOrder()).isEqualTo(3);
        assertThat(mi.getUpdatedBy()).isEqualTo("test-admin");
    }

    @Test
    void createMenuItemRejectsCrossRestaurant() throws Exception {
        seedRestaurant();
        // Second restaurant
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                other-r,Other,Cluj-Napoca,ACTIVE
                """), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                x-menu,crud-r,X Menu,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                x-prod,other-r,X Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "x-menu")
                        .param("productKey", "x-prod")
                        .param("sectionName", "X")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("different restaurants")));
    }

    @Test
    void createMenuItemRejectsDuplicate() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                d-menu,crud-r,D,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                d-prod,crud-r,D Prod,ACTIVE
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                d-menu,d-prod,Starters,10.00,RON,true,0
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "d-menu")
                        .param("productKey", "d-prod")
                        .param("sectionName", "Starters")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void createMenuItemRejectsUnknownMenu() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                um-prod,crud-r,UM Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "nope-menu")
                        .param("productKey", "um-prod")
                        .param("sectionName", "X")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unknown menu_key")));
    }

    @Test
    void createMenuItemDefaultsCurrencyToRon() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                dc-menu,crud-r,DC,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                dc-prod,crud-r,DC Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "dc-menu")
                        .param("productKey", "dc-prod")
                        .param("sectionName", "S")
                        // no currency
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Long menuId = menus.findByMenuKey("dc-menu").orElseThrow().getId();
        Long productId = products.findByProductKey("dc-prod").orElseThrow().getId();
        MenuItem mi = menuItems.findByMenuIdAndProductId(menuId, productId).orElseThrow();
        assertThat(mi.getCurrency()).isEqualTo("RON");
    }

    // ------------------------------------------------------------------ menu-item CRUD

    @Test
    void editMenuItemFormPrePopulatesFields() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                mi-menu,crud-r,MI,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                mi-prod,crud-r,MI Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                mi-menu,mi-prod,Starters,12.50,RON,true,1
                """), false);

        MenuItem mi = menuItems.findByMenuIdAndProductId(
                menus.findByMenuKey("mi-menu").orElseThrow().getId(),
                products.findByProductKey("mi-prod").orElseThrow().getId()).orElseThrow();

        mvc.perform(get("/admin/menu-items/" + mi.getId() + "/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='sectionName'][@value='Starters']").exists())
                .andExpect(xpath("//input[@id='currency'][@value='RON']").exists());
    }

    @Test
    void updateMenuItemChangesFields() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                up-menu,crud-r,Up,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                up-prod,crud-r,Up Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                up-menu,up-prod,Mains,20.00,RON,true,1
                """), false);

        Long miId = menuItems.findByMenuIdAndProductId(
                menus.findByMenuKey("up-menu").orElseThrow().getId(),
                products.findByProductKey("up-prod").orElseThrow().getId()).orElseThrow().getId();

        mvc.perform(post("/admin/menu-items/" + miId)
                        .param("sectionName", "Mains (updated)")
                        .param("price", "22.00")
                        .param("currency", "ron")
                        .param("available", "true")
                        .param("sortOrder", "5")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"));

        MenuItem mi = menuItems.findById(miId).orElseThrow();
        assertThat(mi.getSectionName()).isEqualTo("Mains (updated)");
        assertThat(mi.getPrice()).isEqualByComparingTo("22.00");
        assertThat(mi.getCurrency()).isEqualTo("RON");
        assertThat(mi.getSortOrder()).isEqualTo(5);
    }

    @Test
    void menuItemsListRendersWithoutNpe() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                np-menu,crud-r,NP,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                np-prod,crud-r,NP Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                np-menu,np-prod,Starters,12.00,RON,true,1
                """), false);

        mvc.perform(get("/admin/menu-items")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//td/code[text()='np-menu']").exists());
    }

    @Test
    void deleteMenuItemRemovesRow() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                del-menu,crud-r,Del,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                del-prod,crud-r,Del Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                del-menu,del-prod,Starters,10.00,RON,true,1
                """), false);

        Long miId = menuItems.findByMenuIdAndProductId(
                menus.findByMenuKey("del-menu").orElseThrow().getId(),
                products.findByProductKey("del-prod").orElseThrow().getId()).orElseThrow().getId();

        // Two-step guard: must Hide (available=false) before Delete.
        MenuItem seeded = menuItems.findById(miId).orElseThrow();
        seeded.setAvailable(false);
        menuItems.save(seeded);

        mvc.perform(post("/admin/menu-items/" + miId + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"));

        assertThat(menuItems.findById(miId)).isEmpty();
    }

    @Test
    void deleteVisibleMenuItemIsRejectedAndKeepsRow() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                del2-r,Del 2,Cluj-Napoca,ACTIVE
                """), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                del2-menu,del2-r,Del 2,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                del2-prod,del2-r,Del 2 Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                del2-menu,del2-prod,Starters,10.00,RON,true,1
                """), false);

        Long miId = menuItems.findByMenuIdAndProductId(
                menus.findByMenuKey("del2-menu").orElseThrow().getId(),
                products.findByProductKey("del2-prod").orElseThrow().getId()).orElseThrow().getId();

        // Seeded with available=true, so delete is rejected.
        mvc.perform(post("/admin/menu-items/" + miId + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(menuItems.findById(miId)).isPresent();
    }

    // ------------------------------------------------------------------ V4 metadata (Tier 1B)

    @Test
    void createProductWithMetadataPersistsAllFields() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/products")
                        .param("productKey", "meta-prod")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Meta Prod")
                        .param("weightText", "350g")
                        .param("weightGrams", "350")
                        .param("category", "Pizza")
                        // mixed casing + spaces, with a duplicate to verify dedup
                        .param("tags", "VEGETARIAN,  spicy , vegetarian")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products"));

        Product p = products.findByProductKey("meta-prod").orElseThrow();
        assertThat(p.getWeightGrams()).isEqualTo(350);
        assertThat(p.getCategory()).isEqualTo("Pizza");
        assertThat(p.getTags()).isEqualTo("vegetarian,spicy");
        assertThat(p.getUpdatedBy()).isEqualTo("test-admin");
    }

    @Test
    void createProductRejectsUnknownTag() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/products")
                        .param("productKey", "bad-tag-prod")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Bad Tag")
                        .param("tags", "vegetarian,purple-monkey")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("purple-monkey")));
    }

    @Test
    void createProductRejectsOutOfRangeWeight() throws Exception {
        seedRestaurant();
        mvc.perform(post("/admin/products")
                        .param("productKey", "fat-prod")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Fat")
                        .param("weightGrams", "100500")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("weight_grams")));
    }

    @Test
    void updateProductReplacesMetadata() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                upd-meta,crud-r,Upd Meta,DRAFT
                """), false);

        mvc.perform(post("/admin/products/upd-meta")
                        .param("restaurantKey", "crud-r")
                        .param("name", "Upd Meta")
                        .param("weightGrams", "200")
                        .param("category", "Soup")
                        .param("tags", "vegan")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Product p = products.findByProductKey("upd-meta").orElseThrow();
        assertThat(p.getWeightGrams()).isEqualTo(200);
        assertThat(p.getCategory()).isEqualTo("Soup");
        assertThat(p.getTags()).isEqualTo("vegan");
    }

    @Test
    void createMenuItemWithSpiceLevelPersistsIt() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                sl-menu,crud-r,SL,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                sl-prod,crud-r,SL Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "sl-menu")
                        .param("productKey", "sl-prod")
                        .param("sectionName", "Mains")
                        .param("price", "18.00")
                        .param("currency", "RON")
                        .param("available", "true")
                        .param("sortOrder", "0")
                        .param("spiceLevel", "2")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Long menuId = menus.findByMenuKey("sl-menu").orElseThrow().getId();
        Long productId = products.findByProductKey("sl-prod").orElseThrow().getId();
        MenuItem mi = menuItems.findByMenuIdAndProductId(menuId, productId).orElseThrow();
        assertThat(mi.getSpiceLevel()).isEqualTo(2);
    }

    @Test
    void createMenuItemRejectsOutOfRangeSpiceLevel() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                bad-sl-menu,crud-r,Bad,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                bad-sl-prod,crud-r,Bad Prod,ACTIVE
                """), false);

        mvc.perform(post("/admin/menu-items")
                        .param("menuKey", "bad-sl-menu")
                        .param("productKey", "bad-sl-prod")
                        .param("sectionName", "S")
                        .param("spiceLevel", "7")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("spice_level")));
    }

    @Test
    void updateMenuItemReplacesSpiceLevel() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                usl-menu,crud-r,USL,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                usl-prod,crud-r,USL Prod,DRAFT
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order,spice_level
                usl-menu,usl-prod,Mains,18.00,RON,true,1,1
                """), false);

        Long miId = menuItems.findByMenuIdAndProductId(
                menus.findByMenuKey("usl-menu").orElseThrow().getId(),
                products.findByProductKey("usl-prod").orElseThrow().getId()).orElseThrow().getId();

        mvc.perform(post("/admin/menu-items/" + miId)
                        .param("sectionName", "Mains")
                        .param("price", "18.00")
                        .param("currency", "RON")
                        .param("available", "true")
                        .param("sortOrder", "1")
                        .param("spiceLevel", "3")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(menuItems.findById(miId).orElseThrow().getSpiceLevel()).isEqualTo(3);
    }

    @Test
    void productCsvRoundtripsV4Fields() throws Exception {
        seedRestaurant();
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_text,weight_grams,category,tags,status
                csv-meta,crud-r,CSV Meta,250g,250,Dessert,"sweet,bio",ACTIVE
                """), false);

        Product p = products.findByProductKey("csv-meta").orElseThrow();
        assertThat(p.getWeightGrams()).isEqualTo(250);
        assertThat(p.getCategory()).isEqualTo("Dessert");
        assertThat(p.getTags()).isEqualTo("sweet,bio");
    }

    @Test
    void menuItemCsvRoundtripsSpiceLevel() throws Exception {
        seedRestaurant();
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                csv-sl-menu,crud-r,SL,PERMANENT,DRAFT
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                csv-sl-prod,crud-r,SL Prod,ACTIVE
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order,spice_level
                csv-sl-menu,csv-sl-prod,Mains,18.00,RON,true,1,2
                """), false);

        Long menuId = menus.findByMenuKey("csv-sl-menu").orElseThrow().getId();
        Long productId = products.findByProductKey("csv-sl-prod").orElseThrow().getId();
        assertThat(menuItems.findByMenuIdAndProductId(menuId, productId).orElseThrow().getSpiceLevel())
                .isEqualTo(2);
    }
}
