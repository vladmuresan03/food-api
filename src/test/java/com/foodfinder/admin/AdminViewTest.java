package com.foodfinder.admin;

import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminViewTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;

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
}
