package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.product.ProductRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class UpdatedByAuditTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired RestaurantRepository restaurants;
    @Autowired MenuRepository menus;
    @Autowired ProductRepository products;

    @Test
    void viewControllerSetsUpdatedByOnCreate() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                aud-r,Audit Rest,Cluj-Napoca,ACTIVE
                """), false);

        mvc.perform(post("/admin/menus")
                        .param("menuKey", "aud-m1")
                        .param("restaurantKey", "aud-r")
                        .param("name", "Aud M1")
                        .param("status", "DRAFT")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menus"));

        var menu = menus.findByMenuKey("aud-m1").orElseThrow();
        assertThat(menu.getUpdatedBy()).isEqualTo("test-admin");
    }

    @Test
    void viewControllerSetsUpdatedByOnUpdate() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                aud-r,Audit Rest,Cluj-Napoca,ACTIVE
                """), false);

        mvc.perform(post("/admin/restaurants/aud-r")
                        .param("name", "Updated name")
                        .param("city", "Cluj-Napoca")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        var r = restaurants.findByRestaurantKey("aud-r").orElseThrow();
        assertThat(r.getUpdatedBy()).isEqualTo("test-admin");
    }

    @Test
    void jsonApiSetsUpdatedByOnCreate() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                aud-r,Audit Rest,Cluj-Napoca,ACTIVE
                """), false);

        mvc.perform(post("/admin/api/products")
                        .contentType("application/json")
                        .content("""
                                {
                                  "productKey": "aud-p1",
                                  "restaurantKey": "aud-r",
                                  "name": "Audit Prod",
                                  "status": "ACTIVE"
                                }
                                """)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk());

        var p = products.findByProductKey("aud-p1").orElseThrow();
        assertThat(p.getUpdatedBy()).isEqualTo("test-admin");
    }
}
