package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;

import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.restaurant.Restaurant;
import com.foodfinder.restaurant.RestaurantRepository;
import com.foodfinder.restaurant.RestaurantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AdminViewTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;
    @Autowired RestaurantRepository restaurants;

    // ------------------------------------------------------------------ security

    @Test
    void adminPagesRequireAuth() throws Exception {
        mvc.perform(get("/admin/restaurants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    // ------------------------------------------------------------------ tables render

    @Test
    void emptyRestaurantsPageRendersEmptyState() throws Exception {
        mvc.perform(get("/admin/restaurants")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(xpath("//div[@class='empty']").exists());
    }

    @Test
    void restaurantsPageRendersRealRows() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,latitude,longitude,status
                vw-r1,Rest 1,Cluj-Napoca,46.77,23.55,ACTIVE
                vw-r2,Rest 2,Cluj-Napoca,46.78,23.56,ACTIVE
                """), false);

        mvc.perform(get("/admin/restaurants")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//td/code[text()='vw-r1']").exists())
                .andExpect(xpath("//td/code[text()='vw-r2']").exists());
    }

    @Test
    void productsPageRendersThumbnail() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                vw-r3,Rest 3,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                vw-p1,vw-r3,P 1,ACTIVE
                """), false);

        mvc.perform(get("/admin/products")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//table").exists())
                .andExpect(xpath("//td/code[text()='vw-p1']").exists());
    }

    // ------------------------------------------------------------------ CSV dry-run

    @Test
    void csvDryRunErrorsAreVisibleOnPage() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", """
                        restaurant_key,name,city,status
                        good-r,Good,Cluj-Napoca,ACTIVE
                        bad-r,Bad,Cluj-Napoca,BOGUS
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "true")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_STATUS")));
    }

    // ------------------------------------------------------------------ restaurant CRUD

    @Test
    void newRestaurantFormRenders() throws Exception {
        mvc.perform(get("/admin/restaurants/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='restaurantKey']").exists())
                .andExpect(xpath("//input[@id='name']").exists())
                .andExpect(xpath("//input[@id='latitude']").exists())
                .andExpect(xpath("//input[@id='longitude']").exists());
    }

    @Test
    void createRestaurantHappyPath() throws Exception {
        mvc.perform(post("/admin/restaurants")
                        .param("restaurantKey", "crud-r1")
                        .param("name", "CRUD Test 1")
                        .param("city", "Cluj-Napoca")
                        .param("latitude", "46.77")
                        .param("longitude", "23.55")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/restaurants"))
                .andExpect(flash().attributeExists("successMessage"));

        Restaurant saved = restaurants.findByRestaurantKey("crud-r1").orElseThrow();
        assertThat(saved.getName()).isEqualTo("CRUD Test 1");
        assertThat(saved.getStatus()).isEqualTo(RestaurantStatus.ACTIVE);
    }

    @Test
    void createRestaurantWithBadSlugShowsError() throws Exception {
        mvc.perform(post("/admin/restaurants")
                        .param("restaurantKey", "Bad Slug With Spaces")
                        .param("name", "X")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("slug")));
    }

    @Test
    void createRestaurantWithDuplicateKeyShowsError() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                dup-r,Dup,Cluj-Napoca,ACTIVE
                """), false);

        mvc.perform(post("/admin/restaurants")
                        .param("restaurantKey", "dup-r")
                        .param("name", "Dup again")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void createRestaurantWithMismatchedGeoShowsError() throws Exception {
        mvc.perform(post("/admin/restaurants")
                        .param("restaurantKey", "geo-r")
                        .param("name", "Geo")
                        .param("latitude", "46.77")
                        // longitude missing
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//div[@class='errors']").exists())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("together")));
    }

    @Test
    void editRestaurantFormPrePopulatesFields() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,latitude,longitude,status
                edit-r,Edit Me,Cluj-Napoca,46.77,23.55,ACTIVE
                """), false);

        mvc.perform(get("/admin/restaurants/edit-r/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@id='restaurantKey'][@readonly]").exists())
                .andExpect(xpath("//input[@id='name'][@value='Edit Me']").exists())
                .andExpect(xpath("//input[@id='latitude'][@value='46.77']").exists());
    }

    @Test
    void updateRestaurantChangesFields() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                upd-r,Original,Cluj-Napoca,DRAFT
                """), false);

        mvc.perform(post("/admin/restaurants/upd-r")
                        .param("name", "Updated name")
                        .param("city", "Cluj-Napoca")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/restaurants"));

        Restaurant r = restaurants.findByRestaurantKey("upd-r").orElseThrow();
        assertThat(r.getName()).isEqualTo("Updated name");
        assertThat(r.getStatus()).isEqualTo(RestaurantStatus.ACTIVE);
    }

    @Test
    void archiveRestaurantFlipsStatus() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                arc-r,Arc,Cluj-Napoca,ACTIVE
                """), false);

        mvc.perform(post("/admin/restaurants/arc-r/archive")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/restaurants"));

        assertThat(restaurants.findByRestaurantKey("arc-r").orElseThrow().getStatus())
                .isEqualTo(RestaurantStatus.ARCHIVED);
    }

    @Test
    void activateRestaurantFlipsStatus() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                act-r,Act,Cluj-Napoca,ARCHIVED
                """), false);

        mvc.perform(post("/admin/restaurants/act-r/activate")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/restaurants"));

        assertThat(restaurants.findByRestaurantKey("act-r").orElseThrow().getStatus())
                .isEqualTo(RestaurantStatus.ACTIVE);
    }

    @Test
    void editRestaurantUnknownKeyIsNotFound() throws Exception {
        mvc.perform(get("/admin/restaurants/nope/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }
}
