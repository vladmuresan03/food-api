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

        mvc.perform(post("/admin/menu-items/" + miId + "/delete")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu-items"));

        assertThat(menuItems.findById(miId)).isEmpty();
    }
}
