package com.foodfinder.csv;

import com.foodfinder.IntegrationTest;
import com.foodfinder.admin.CsvImportLogRepository;
import com.foodfinder.admin.TestDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CsvApiImportTest {

    @Autowired MockMvc mvc;
    @Autowired CsvImportLogRepository importLog;
    @Autowired TestDataCleanup cleanup;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;

    @BeforeEach
    void seed() throws Exception {
        // Seed a restaurant + product so nutrition/ingredients imports
        // have a valid product_key to attach to. Per-row errors for
        // unknown products are tested separately in
        // NutritionIngredientsCsvTest.
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                api-nr,API NR,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_grams,status
                api-pizza,api-nr,API Pizza,400,ACTIVE
                """), false);
    }

    @AfterEach
    void cleanup() {
        cleanup.wipeCatalogData();
    }

    @Test
    void jsonApiImportReturnsStructuredReport() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rest.csv", "text/csv", """
                        restaurant_key,name,city,status
                        api-r1,API 1,Cluj-Napoca,ACTIVE
                        api-r2,API 2,Cluj-Napoca,ACTIVE
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.slug").value("restaurants"))
                .andExpect(jsonPath("$.filename").value("rest.csv"))
                .andExpect(jsonPath("$.actor").value("test-admin"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void jsonApiImportReportsRowErrors() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", """
                        restaurant_key,name,city,status
                        ok-r,OK,Cluj-Napoca,ACTIVE
                        bad-r,Bad,Cluj-Napoca,BOGUS
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(2))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_STATUS"));
    }

    @Test
    void jsonApiImportRejectsUnknownSlug() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/nonexistent")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void jsonApiImportRequiresAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------- nutrition + ingredients

    @Test
    void jsonApiImportNutritionHappyPath() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "nutrition.csv", "text/csv", """
                        product_key,basis,energy_kcal,fat_g,sat_fat_g,carbs_g,sugars_g,protein_g,salt_g,fiber_g,source_url,last_verified_at
                        api-pizza,per_100g,290.00,12.00,5.00,33.00,4.00,12.00,1.40,2.5,https://example.com/p.pdf,2026-08-01
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/nutrition")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.slug").value("nutrition"))
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.errorCount").value(0));
    }

    @Test
    void jsonApiImportIngredientsHappyPath() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ingredients.csv", "text/csv", """
                        product_key,position,name,is_allergen,allergen_code,percentage,origin_country
                        api-pizza,1,Făină de grâu,true,gluten,40.00,RO
                        api-pizza,2,Roșii,false,,,ES
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/api/csv/ingredients")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.slug").value("ingredients"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.errorCount").value(0));
    }

    @Test
    void jsonApiImportNutritionExportsBack() throws Exception {
        // Round-trip: POST nutrition, then GET the same slug and assert
        // the export contains the row we just inserted.
        mvc.perform(multipart("/admin/api/csv/nutrition")
                        .file(new MockMultipartFile("file", "n.csv", "text/csv", """
                                product_key,basis,energy_kcal,fat_g,sat_fat_g,carbs_g,sugars_g,protein_g,salt_g,fiber_g
                                api-pizza,per_100g,200,5,1,30,2,8,1.0,1.0
                                """.getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/admin/api/csv/nutrition")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("api-pizza")));
    }
}
