package com.foodfinder.publicapi;

import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
}
