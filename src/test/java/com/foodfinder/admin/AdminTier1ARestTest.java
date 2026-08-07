package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST CRUD tests for the Tier 1A controllers (nutrition + ingredients).
 * Form path is tested in {@link com.foodfinder.publicapi.PublicApiTier1ATest}
 * via the public API exposure; the form itself is a thin wrapper over
 * the same REST endpoints.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AdminTier1ARestTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                rest-t1a,Rest T1A,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                prod-t1a,rest-t1a,Prod T1A,ACTIVE
                """), false);
    }

    // ------------------------------------------------------------------ nutrition

    @Test
    void putNutritionCreatesAndThenUpdates() throws Exception {
        String body = """
                {"basis":"per_100g","energyKcal":290.0,"fatG":12.0,"satFatG":5.0,
                 "carbsG":33.0,"sugarsG":4.0,"proteinG":12.0,"saltG":1.40,
                 "fiberG":2.5,"sourceUrl":"https://example.com/spec.pdf",
                 "lastVerifiedAt":"2026-08-01"}
                """;
        mvc.perform(put("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("per_100g"))
                .andExpect(jsonPath("$.energyKcal").value(290.0))
                .andExpect(jsonPath("$.lastVerifiedAt").value("2026-08-01"));

        // GET round-trip
        mvc.perform(get("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saltG").value(1.400));
    }

    @Test
    void putNutritionRejectsInvalidBasis() throws Exception {
        String body = """
                {"basis":"per_gram","energyKcal":100}
                """;
        mvc.perform(put("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void getMissingNutritionReturns404() throws Exception {
        mvc.perform(get("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteNutritionRemovesIt() throws Exception {
        // Seed via PUT then delete
        mvc.perform(put("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"energyKcal\":100.0}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/api/products/prod-t1a/nutrition")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ ingredients

    @Test
    void replaceAllIngredientsCreatesAndPreservesOrder() throws Exception {
        String body = """
                [
                  {"position":1,"name":"Wheat flour","isAllergen":true,"allergenCode":"gluten","percentage":40.00,"originCountry":"RO"},
                  {"position":2,"name":"Tomato sauce","isAllergen":false,"percentage":30.00,"originCountry":"IT"},
                  {"position":3,"name":"Mozzarella","isAllergen":true,"allergenCode":"milk","percentage":20.00,"originCountry":"RO"}
                ]
                """;
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[0].name").value("Wheat flour"))
                .andExpect(jsonPath("$[0].isAllergen").value(true))
                .andExpect(jsonPath("$[0].allergenCode").value("gluten"))
                .andExpect(jsonPath("$[2].allergenCode").value("milk"));
    }

    @Test
    void replaceAllIngredientsRejectsUnknownAllergenCode() throws Exception {
        String body = """
                [{"position":1,"name":"Plastic","isAllergen":true,"allergenCode":"rubber"}]
                """;
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void replaceAllIngredientsRejectsIsAllergenWithoutCode() throws Exception {
        String body = """
                [{"position":1,"name":"Mystery","isAllergen":true}]
                """;
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void replaceAllIngredientsWipesAndReplaces() throws Exception {
        // First: 3 ingredients
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"position":1,"name":"A"},{"position":2,"name":"B"},{"position":3,"name":"C"}]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Now: 1 ingredient. The previous 3 must be gone.
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"position":1,"name":"OnlyOne"}]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("OnlyOne"));
    }

    @Test
    void deleteAllIngredientsRemovesThem() throws Exception {
        mvc.perform(put("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"position":1,"name":"A"}]
                                """))
                .andExpect(status().isOk());

        mvc.perform(delete("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/api/products/prod-t1a/ingredients")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
