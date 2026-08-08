package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.MenuItemCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.MenuItem;
import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

/**
 * Verifies the form-based photo edit + per-context upload pages that the
 * admin UI uses to reassign photos and pre-fill uploads.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PhotoEditTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;
    @Autowired PhotoRepository photos;
    @Autowired ProductRepository products;
    @Autowired MenuItemRepository menuItems;
    @Autowired TestDataCleanup cleanup;

    private static final String REST = "pe-r";
    private static final String MENU = "pe-m";
    private static final String PROD_A = "pe-p-a";
    private static final String PROD_B = "pe-p-b";

    /** Two real 800x600 JPEGs (different fill colours so the SHA differs). */
    private static byte[] jpegA() throws IOException { return sampleJpeg(Color.RED); }
    private static byte[] jpegB() throws IOException { return sampleJpeg(Color.BLUE); }

    @BeforeEach
    void seed() throws Exception {
        cleanup.wipeCatalogData();
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                %s,PE R,Cluj-Napoca,ACTIVE
                """.formatted(REST)), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                %s,%s,PE M,PERMANENT,PUBLISHED
                """.formatted(MENU, REST)), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                %s,%s,PE P A,ACTIVE
                %s,%s,PE P B,ACTIVE
                """.formatted(PROD_A, REST, PROD_B, REST)), false);
        menuItemCsv.parse(new StringReader("""
                menu_key,product_key,section_name,price,currency,available,sort_order
                %s,%s,Starters,10.00,RON,true,0
                """.formatted(MENU, PROD_A)), false);
    }

    @AfterEach
    void wipe() {
        cleanup.wipeCatalogData();
    }

    // ------------------------------------------------------------------ edit page

    @Test
    void editPageRendersCurrentValues() throws Exception {
        Photo p = uploadPhoto("foo", PROD_A, true);

        mvc.perform(get("/admin/photos/" + p.getPhotoKey() + "/edit")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//code[text()='" + p.getPhotoKey() + "']").exists())
                .andExpect(xpath("//code[text()='" + REST + "']").exists())
                .andExpect(xpath("//input[@id='productKey']/@value").string(PROD_A))
                .andExpect(xpath("//input[@id='altText']/@value").string("foo"));
    }

    @Test
    void updateReassignsProductAndTogglesPrimary() throws Exception {
        Photo p = uploadPhoto("foo", PROD_A, true);

        mvc.perform(post("/admin/photos/" + p.getPhotoKey())
                        .param("productKey", PROD_B)
                        .param("altText", "bar")
                        .param("isPrimary", "false")
                        .param("status", "ACTIVE")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/photos"))
                .andExpect(flash().attributeExists("successMessage"));

        Photo reloaded = photos.findByPhotoKey(p.getPhotoKey()).orElseThrow();
        assertThat(reloaded.getAltText()).isEqualTo("bar");
        assertThat(products.findById(reloaded.getProductId()).orElseThrow().getProductKey())
                .isEqualTo(PROD_B);
        assertThat(reloaded.isPrimaryPhoto()).isFalse();
    }

    @Test
    void updateToRestaurantLevelClearsProduct() throws Exception {
        Photo p = uploadPhoto("foo", PROD_A, true);

        mvc.perform(post("/admin/photos/" + p.getPhotoKey())
                        .param("productKey", "")
                        .param("altText", "gallery")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Photo reloaded = photos.findByPhotoKey(p.getPhotoKey()).orElseThrow();
        assertThat(reloaded.getProductId()).isNull();
    }

    @Test
    void updateRejectsProductFromDifferentRestaurant() throws Exception {
        // Seed a second restaurant with its own product.
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                other-r,Other,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                other-p,other-r,Other P,ACTIVE
                """), false);

        Photo p = uploadPhoto("foo", PROD_A, true);

        mvc.perform(post("/admin/photos/" + p.getPhotoKey())
                        .param("productKey", "other-p")
                        .param("altText", "cross")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is4xxClientError());

        // Row must be unchanged.
        Photo reloaded = photos.findByPhotoKey(p.getPhotoKey()).orElseThrow();
        assertThat(products.findById(reloaded.getProductId()).orElseThrow().getProductKey())
                .isEqualTo(PROD_A);
    }

    @Test
    void updateCanArchiveViaStatusField() throws Exception {
        Photo p = uploadPhoto("foo", PROD_A, true);

        mvc.perform(post("/admin/photos/" + p.getPhotoKey())
                        .param("status", "ARCHIVED")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(photos.findByPhotoKey(p.getPhotoKey()).orElseThrow().getStatus())
                .isEqualTo(PhotoStatus.ARCHIVED);
    }

    // ------------------------------------------------------------------ per-context upload

    @Test
    void uploadForProductPrefillsRestaurantAndProduct() throws Exception {
        mvc.perform(get("/admin/products/" + PROD_A + "/photos/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@name='restaurantKey']/@value").string(REST))
                .andExpect(xpath("//input[@name='productKey']/@value").string(PROD_A));
    }

    @Test
    void uploadForRestaurantPrefillsOnlyRestaurant() throws Exception {
        mvc.perform(get("/admin/restaurants/" + REST + "/photos/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@name='restaurantKey']/@value").string(REST));
    }

    @Test
    void uploadForMenuItemResolvesBothKeys() throws Exception {
        Long productId = products.findByProductKey(PROD_A).orElseThrow().getId();
        long miId = menuItems.findByProductId(productId).stream()
                .map(MenuItem::getId)
                .findFirst()
                .orElseThrow();

        mvc.perform(get("/admin/menu-items/" + miId + "/photos/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(xpath("//input[@name='restaurantKey']/@value").string(REST))
                .andExpect(xpath("//input[@name='productKey']/@value").string(PROD_A));
    }

    @Test
    void uploadForUnknownProductReturns404() throws Exception {
        mvc.perform(get("/admin/products/no-such-p/photos/new")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ happy-path full upload via the form

    @Test
    void rawFormUploadSucceeds() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", jpegA());

        mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", REST)
                        .param("productKey", PROD_A)
                        .param("altText", "uploaded from form")
                        .param("isPrimary", "true")
                        .param("sourceType", "UPLOAD")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(photos.findAll())
                .anyMatch(p -> "uploaded from form".equals(p.getAltText())
                        && p.getStatus() == PhotoStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ helpers

    private Photo uploadPhoto(String altText, String productKey, boolean primary) throws Exception {
        byte[] bytes = altText.hashCode() % 2 == 0 ? jpegA() : jpegB();
        MockMultipartFile file = new MockMultipartFile(
                "file", altText + ".jpg", "image/jpeg", bytes);
        // POST /admin/api/photos as a multipart and return the persisted row.
        mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", REST)
                        .param("productKey", productKey)
                        .param("altText", altText)
                        .param("isPrimary", Boolean.toString(primary))
                        .param("sourceType", "UPLOAD")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());
        // Find the just-uploaded row by alt text (the body is JSON).
        return photos.findAll().stream()
                .filter(p -> altText.equals(p.getAltText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("upload didn't persist: " + altText));
    }

    /** Build a real 800x600 JPEG filled with a single colour. */
    private static byte[] sampleJpeg(Color color) throws IOException {
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 800, 600);
        g.setColor(Color.WHITE);
        g.drawString("PhotoEditTest", 50, 50);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
