package com.foodfinder.csv;

import com.foodfinder.IntegrationTest;
import com.foodfinder.admin.TestDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CsvBundleApiTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataCleanup cleanup;

    @AfterEach
    void cleanup() {
        cleanup.wipeCatalogData();
    }

    @Test
    void bundleApiReturnsStructuredJson() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        ba-r1,BA 1,Cluj-Napoca,ACTIVE
                        """,
                "menus.csv", """
                        menu_key,restaurant_key,name,menu_type,status
                        ba-m1,ba-r1,M1,PERMANENT,DRAFT
                        """);

        mvc.perform(multipart("/admin/api/csv/bundle")
                        .file(new MockMultipartFile("file", "b.zip",
                                "application/zip", zip))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.bundleFilename").value("b.zip"))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.totalInserted").value(2))
                .andExpect(jsonPath("$.totalUpdated").value(0))
                .andExpect(jsonPath("$.totalErrors").value(0))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries[?(@.slug=='restaurants')]").exists())
                .andExpect(jsonPath("$.entries[?(@.slug=='menus')]").exists());
    }

    @Test
    void bundleApiMarksSkippedResourcesAfterFailure() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        ba-r1,BA 1,Cluj-Napoca,ACTIVE
                        """,
                "products.csv", """
                        product_key,restaurant_key,name,status
                        ba-p1,NONEXISTENT,Prod 1,DRAFT
                        """);

        mvc.perform(multipart("/admin/api/csv/bundle")
                        .file(new MockMultipartFile("file", "b.zip",
                                "application/zip", zip))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.totalErrors").value(1))
                .andExpect(jsonPath("$.entries[?(@.slug=='photos' && @.skipped==true)]").exists());
    }

    @Test
    void bundleApiRequiresAuth() throws Exception {
        byte[] zip = zipOf("restaurants.csv", """
                restaurant_key,name,city,status
                """);
        mvc.perform(multipart("/admin/api/csv/bundle")
                        .file(new MockMultipartFile("file", "b.zip",
                                "application/zip", zip))
                        .param("dryRun", "false"))
                .andExpect(status().isUnauthorized());
    }

    private static byte[] zipOf(String... pairs) throws java.io.IOException {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/content pairs");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buf.toByteArray();
    }
}
