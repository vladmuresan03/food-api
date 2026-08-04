package com.foodfinder.admin;

import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminApiTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                adm-r1,R 1,Cluj-Napoca,ACTIVE
                adm-r2,R 2,Cluj-Napoca,ACTIVE
                """), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                adm-m1,adm-r1,Main,PERMANENT,PUBLISHED
                adm-m2,adm-r2,M 2,PERMANENT,PUBLISHED
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                adm-p1,adm-r1,P 1,ACTIVE
                adm-p2,adm-r2,P 2,ACTIVE
                """), false);
    }

    // ------------------------------------------------------------------ security

    @Test
    void unauthenticatedAdminRequestIsRejected() throws Exception {
        mvc.perform(get("/admin/api/restaurants"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ restaurants

    @Test
    void createRestaurant() throws Exception {
        mvc.perform(post("/admin/api/restaurants")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "restaurantKey": "new-rest",
                                  "name": "New Rest",
                                  "city": "Cluj-Napoca",
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantKey").value("new-rest"));
    }

    @Test
    void duplicateKeyIs409() throws Exception {
        mvc.perform(post("/admin/api/restaurants")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantKey":"adm-r1","name":"Dup","city":"Cluj-Napoca","status":"ACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void updateRestaurantStatus() throws Exception {
        mvc.perform(patch("/admin/api/restaurants/adm-r1/status")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    // ------------------------------------------------------------------ menus

    @Test
    void createMenu() throws Exception {
        mvc.perform(post("/admin/api/menus")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "menuKey": "new-menu",
                                  "restaurantKey": "adm-r1",
                                  "name": "New Menu",
                                  "menuType": "PERMANENT",
                                  "status": "DRAFT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuKey").value("new-menu"));
    }

    @Test
    void updateMenu() throws Exception {
        mvc.perform(put("/admin/api/menus/adm-m1")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "menuKey": "adm-m1",
                                  "restaurantKey": "adm-r1",
                                  "name": "Renamed",
                                  "menuType": "WEEKLY",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.menuType").value("WEEKLY"));
    }

    // ------------------------------------------------------------------ products

    @Test
    void createProduct() throws Exception {
        mvc.perform(post("/admin/api/products")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productKey": "new-prod",
                                  "restaurantKey": "adm-r1",
                                  "name": "New Product",
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productKey").value("new-prod"));
    }

    // ------------------------------------------------------------------ menu_items

    @Test
    void createMenuItem() throws Exception {
        mvc.perform(post("/admin/api/menu-items")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "menuKey": "adm-m1",
                                  "productKey": "adm-p1",
                                  "sectionName": "Mâncare",
                                  "price": 29.00,
                                  "currency": "RON",
                                  "available": true,
                                  "sortOrder": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuKey").value("adm-m1"))
                .andExpect(jsonPath("$.price").value(29.00));
    }

    @Test
    void crossRestaurantMenuItemIs409() throws Exception {
        mvc.perform(post("/admin/api/menu-items")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "menuKey": "adm-m1",
                                  "productKey": "adm-p2",
                                  "sectionName": "X",
                                  "price": 10,
                                  "currency": "RON"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteMenuItem() throws Exception {
        // create first
        String body = mvc.perform(post("/admin/api/menu-items")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuKey":"adm-m1","productKey":"adm-p1","sectionName":"X","price":10,"currency":"RON"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // extract id from the JSON response
        String id = body.replaceAll(".*\"id\":(\\d+).*", "$1");

        mvc.perform(delete("/admin/api/menu-items/" + id)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNoContent());
    }
}
