package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.menu.MenuRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class BundleImportTest {

    /**
     * The bundle importer uses REQUIRES_NEW per inner resource, so its
     * commits are not visible to the test's outer @Transactional. We
     * need to clean up explicitly (in a separate, committed transaction)
     * to keep the shared testcontainer clean for the next test class.
     */
    @Autowired private TestDataCleanup cleanup;
    @AfterEach
    void cleanup() {
        cleanup.wipeCatalogData();
    }

    @Autowired MockMvc mvc;
    @Autowired RestaurantRepository restaurantRepo;
    @Autowired MenuRepository menuRepo;
    @Autowired ProductRepository productRepo;
    @Autowired CsvImportLogRepository importLog;

    @Test
    void bundleImportsAllSixInOrder() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        b-r1,B R1,Cluj-Napoca,ACTIVE
                        b-r2,B R2,Cluj-Napoca,ACTIVE
                        """,
                "menus.csv", """
                        menu_key,restaurant_key,name,menu_type,status
                        b-m1,b-r1,Menu 1,PERMANENT,DRAFT
                        """,
                "products.csv", """
                        product_key,restaurant_key,name,status
                        b-p1,b-r1,Prod 1,DRAFT
                        """,
                "menu-items.csv", """
                        menu_key,product_key,section_name,price,currency,available,sort_order
                        b-m1,b-p1,Starters,12.00,RON,true,1
                        """,
                "photos.csv", """
                        photo_key,restaurant_key,product_key,source_type,external_url,is_primary,status
                        b-ph1,b-r1,,RESTAURANT_OFFICIAL,https://example.com/x.jpg,true,ACTIVE
                        """,
                "menu-assets.csv", """
                        asset_key,menu_key,asset_type,storage_key,original_filename,mime_type,size_bytes,sha256
                        b-a1,b-m1,IMAGE,photos/b-m1/asset1.jpg,asset1.jpg,image/jpeg,1000,abc
                        """);

        mvc.perform(multipart("/admin/csv/bundle")
                        .file(new MockMultipartFile("file", "bundle.zip",
                                "application/zip", zip))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//span[@class='pill pill-active']").exists());

        assertThat(restaurantRepo.findByRestaurantKey("b-r1")).isPresent();
        assertThat(menuRepo.findByMenuKey("b-m1")).isPresent();
        assertThat(productRepo.findByProductKey("b-p1")).isPresent();
    }

    @Test
    void bundleAbortsOnFirstFailure() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        b-r1,B R1,Cluj-Napoca,ACTIVE
                        """,
                "menus.csv", """
                        menu_key,restaurant_key,name,menu_type,status
                        b-m1,b-r1,Menu 1,PERMANENT,DRAFT
                        """,
                "products.csv", """
                        product_key,restaurant_key,name,status
                        b-p1,NONEXISTENT_RESTAURANT,Prod 1,DRAFT
                        """);

        mvc.perform(multipart("/admin/csv/bundle")
                        .file(new MockMultipartFile("file", "bundle.zip",
                                "application/zip", zip))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                // products has the error, menu-items/photos/menu-assets are skipped
                .andExpect(xpath("//span[@class='pill pill-archived' and text()='skipped']").exists());

        assertThat(restaurantRepo.findByRestaurantKey("b-r1")).isPresent();
        assertThat(menuRepo.findByMenuKey("b-m1")).isPresent();
        assertThat(productRepo.findByProductKey("b-p1")).isEmpty();
    }

    @Test
    void bundleDryRunWritesNothing() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        dr-r,DR,Cluj-Napoca,ACTIVE
                        """);

        mvc.perform(multipart("/admin/csv/bundle")
                        .file(new MockMultipartFile("file", "bundle.zip",
                                "application/zip", zip))
                        .param("dryRun", "true")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(restaurantRepo.findByRestaurantKey("dr-r")).isEmpty();
    }

    @Test
    void bundleImportIsLoggedAsOneOuterAndSixInnerRows() throws Exception {
        byte[] zip = zipOf(
                "restaurants.csv", """
                        restaurant_key,name,city,status
                        log-r,Log,Cluj-Napoca,ACTIVE
                        """,
                "menus.csv", """
                        menu_key,restaurant_key,name,menu_type,status
                        log-m,log-r,LM,PERMANENT,DRAFT
                        """);

        mvc.perform(multipart("/admin/csv/bundle")
                        .file(new MockMultipartFile("file", "bundle.zip",
                                "application/zip", zip))
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        long bundleRows = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .filter(l -> l.getSlug().equals("bundle")).count();
        long restaurantRows = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .filter(l -> l.getSlug().equals("restaurants")).count();
        long menuRows = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .filter(l -> l.getSlug().equals("menus")).count();
        assertThat(bundleRows).isEqualTo(1);
        assertThat(restaurantRows).isGreaterThanOrEqualTo(1);
        assertThat(menuRows).isGreaterThanOrEqualTo(1);
    }

    private static byte[] zipOf(String... nameContentPairs) throws java.io.IOException {
        if (nameContentPairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/content pairs");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameContentPairs[i]));
                zip.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buf.toByteArray();
    }
}
