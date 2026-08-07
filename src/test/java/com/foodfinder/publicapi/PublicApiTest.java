package com.foodfinder.publicapi;

import com.foodfinder.IntegrationTest;

import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class PublicApiTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,latitude,longitude,status
                pub-r1,Pub R 1,Cluj-Napoca,46.77,23.55,ACTIVE
                pub-r2,Pub R 2,Cluj-Napoca,46.78,23.56,ACTIVE
                pub-archived,Pub Archived,Cluj-Napoca,46.79,23.57,ARCHIVED
                """), false);

        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                pub-r1-main,pub-r1,Main Menu,PERMANENT,PUBLISHED
                pub-r1-daily,pub-r1,Daily,DAILY,DRAFT
                pub-r2-main,pub-r2,R2 Main,PERMANENT,PUBLISHED
                """), false);

        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,description,weight_text,status
                pub-pizza,pub-r1,Pizza,Classic pizza,400 g,ACTIVE
                pub-pasta,pub-r1,Pasta,Italian pasta,300 g,ACTIVE
                pub-archived,pub-r2,Old Dish,,,ARCHIVED
                """), false);

        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                pub-r1-main,pub-pizza,Mâncare,29.00,RON,true,0
                pub-r1-main,pub-pasta,Mâncare,24.50,RON,true,1
                pub-r2-main,pub-archived,Mâncare,15.00,RON,true,0
                """), false);
    }

    @Test
    void listRestaurantsExcludesArchivedByDefault() throws Exception {
        mvc.perform(get("/api/restaurants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='pub-r1')]").exists())
                .andExpect(jsonPath("$[?(@.key=='pub-r2')]").exists())
                .andExpect(jsonPath("$[?(@.key=='pub-archived')]").doesNotExist());
    }

    @Test
    void listRestaurantsPaginationAppliesAfterFilter() throws Exception {
        // Regression: page 0 must contain ACTIVE rows even when earlier
        // alphabetically-sorted rows are ARCHIVED. The bug applied Pageable
        // before the status filter, so with the names below the first
        // page would only contain the (archived) "Alpha Aaa" row.
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,latitude,longitude,status
                alpha-aaa,Alpha Aaa,Cluj-Napoca,46.77,23.55,ARCHIVED
                alpha-aab,Alpha Aab,Cluj-Napoca,46.77,23.55,ARCHIVED
                zebra-zzz,Zebra Zzz,Cluj-Napoca,46.77,23.55,ACTIVE
                """), false);

        // With the bug: size=1 page=0 returns the alphabetically first
        // global row (Alpha Aaa, ARCHIVED), then status filter strips it,
        // so the response is []. With the fix, page 0 returns the first
        // ACTIVE row by name = pub-r1.
        mvc.perform(get("/api/restaurants").param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("pub-r1"));
    }

    @Test
    void restaurantDetailReturnsProductCount() throws Exception {
        mvc.perform(get("/api/restaurants/pub-r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("pub-r1"))
                .andExpect(jsonPath("$.productCount").value(2))
                .andExpect(jsonPath("$.menus[0].key").value("pub-r1-main"));
    }

    @Test
    void menuDetailReturnsNestedSections() throws Exception {
        mvc.perform(get("/api/menus/pub-r1-main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("pub-r1-main"))
                .andExpect(jsonPath("$.restaurant.key").value("pub-r1"))
                .andExpect(jsonPath("$.sections[0].name").value("Mâncare"))
                .andExpect(jsonPath("$.sections[0].items[0].productKey").value("pub-pizza"))
                .andExpect(jsonPath("$.sections[0].items[0].price").value(29.00));
    }

    @Test
    void draftMenuIs404() throws Exception {
        mvc.perform(get("/api/menus/pub-r1-daily"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void missingRestaurantIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/restaurants/no-such"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void productsFilterByRestaurantKey() throws Exception {
        mvc.perform(get("/api/products").param("restaurantKey", "pub-r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='pub-pizza')]").exists())
                .andExpect(jsonPath("$[?(@.key=='pub-pasta')]").exists())
                .andExpect(jsonPath("$[?(@.key=='pub-archived')]").doesNotExist());
    }

    @Test
    void menuDetailExposesTier1BFields() throws Exception {
        // Seed a product with the V4 metadata fields, then a menu_item with spice_level,
        // and verify the public menu API surfaces all of them.
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_grams,category,tags,status
                pub-pizza-meta,pub-r1,Pizza Meta,400,Pizza,"spicy,vegetarian",ACTIVE
                """), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order,spice_level
                pub-r1-main,pub-pizza-meta,Pizza,32.00,RON,true,5,2
                """), false);

        mvc.perform(get("/api/menus/pub-r1-main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[?(@.name=='Pizza')].items[0].weightGrams").value(400))
                .andExpect(jsonPath("$.sections[?(@.name=='Pizza')].items[0].category").value("Pizza"))
                .andExpect(jsonPath("$.sections[?(@.name=='Pizza')].items[0].tags").value("spicy,vegetarian"))
                .andExpect(jsonPath("$.sections[?(@.name=='Pizza')].items[0].spiceLevel").value(2));
    }

    @Test
    void productDetailExposesTier1BFields() throws Exception {
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_grams,category,tags,status
                pub-detail-meta,pub-r1,Detail Meta,250,Soup,vegan,ACTIVE
                """), false);

        mvc.perform(get("/api/products/pub-detail-meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightGrams").value(250))
                .andExpect(jsonPath("$.category").value("Soup"))
                .andExpect(jsonPath("$.tags").value("vegan"));
    }
}
