package com.foodfinder.photo;

import com.foodfinder.csv.MenuCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import com.foodfinder.menu.MenuAsset;
import com.foodfinder.menu.MenuAssetStorageService;
import com.foodfinder.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileUploadTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired PhotoRepository photos;
    @Autowired PhotoStorageService photoService;
    @Autowired MenuAssetStorageService assetService;
    @Autowired FileStorage storage;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                up-r1,Up R 1,Cluj-Napoca,ACTIVE
                """), false);
        menuCsv.parse(new StringReader("""
                menu_key,restaurant_key,name,menu_type,status
                up-m1,up-r1,Main,PERMANENT,PUBLISHED
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                up-p1,up-r1,P 1,ACTIVE
                """), false);
    }

    private byte[] sampleJpeg() throws IOException {
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 800, 600);
        g.setColor(Color.WHITE);
        g.drawString("Test", 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ upload

    @Test
    void validJpegUpload() throws Exception {
        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", new ByteArrayInputStream(bytes));

        mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoKey").exists())
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.width").value(800))
                .andExpect(jsonPath("$.height").value(600))
                .andExpect(jsonPath("$.sha256").exists());
    }

    @Test
    void invalidMimeIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "not an image".getBytes());

        mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pdfMenuUpload() throws Exception {
        // a minimal valid PDF (PDF-1.4 header + EOF marker)
        byte[] minimalPdf = ("%PDF-1.4\n%âãÏÓ\n1 0 obj<<>>endobj\n"
                + "trailer<<>>\n%%EOF\n").getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "menu.pdf", "application/pdf", new ByteArrayInputStream(minimalPdf));

        mvc.perform(multipart("/admin/api/menus/up-m1/assets")
                        .file(file)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetKey").exists())
                .andExpect(jsonPath("$.assetType").value("PDF"))
                .andExpect(jsonPath("$.sizeBytes").exists())
                .andExpect(jsonPath("$.sha256").exists());
    }

    @Test
    void urlAssetIsRegistered() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/admin/api/menus/up-m1/assets/url")
                        .with(httpBasic("test-admin", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceUrl": "https://bigbelly.ro/menu.pdf",
                                  "assetType": "URL",
                                  "sizeBytes": 12345
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetType").value("URL"));
    }

    // ------------------------------------------------------------------ photo serving

    @Test
    void uploadedPhotoIsServed() throws Exception {
        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "t.jpg", "image/jpeg", new ByteArrayInputStream(bytes));
        String body = mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .with(httpBasic("test-admin", "test-password")))
                .andReturn().getResponse().getContentAsString();
        String photoKey = body.replaceAll(".*\"photoKey\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/photos/" + photoKey + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
    }

    @Test
    void uploadedPhotoHasThumbnail() throws Exception {
        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "t.jpg", "image/jpeg", new ByteArrayInputStream(bytes));
        String body = mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .with(httpBasic("test-admin", "test-password")))
                .andReturn().getResponse().getContentAsString();
        String photoKey = body.replaceAll(".*\"photoKey\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/photos/" + photoKey + "/thumbnail"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
    }

    @Test
    void archivedPhotoNoLongerServed() throws Exception {
        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "t.jpg", "image/jpeg", new ByteArrayInputStream(bytes));
        String body = mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .with(httpBasic("test-admin", "test-password")))
                .andReturn().getResponse().getContentAsString();
        String photoKey = body.replaceAll(".*\"photoKey\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/admin/api/photos/" + photoKey)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/photos/" + photoKey + "/content"))
                .andExpect(status().isNotFound());
    }

    @Test
    void photoBelongsToForeignRestaurantIs409() throws Exception {
        // create second restaurant
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                up-r2,Up R 2,Cluj-Napoca,ACTIVE
                """), false);

        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "t.jpg", "image/jpeg", new ByteArrayInputStream(bytes));

        // upload photo for up-r2, then try to associate it with up-r1's product
        String body = mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r2")
                        .with(httpBasic("test-admin", "test-password")))
                .andReturn().getResponse().getContentAsString();
        String photoKey = body.replaceAll(".*\"photoKey\":\"([^\"]+)\".*", "$1");

        mvc.perform(put("/admin/api/photos/" + photoKey)
                        .param("productKey", "up-p1")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isConflict());
    }

    @Test
    void pathTraversalInStorageKeyIsRejected() {
        assertThatThrownBy(() -> storage.resolve("../../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void photoUploadWithProductBelongsToSameRestaurant() throws Exception {
        byte[] bytes = sampleJpeg();
        MockMultipartFile file = new MockMultipartFile(
                "file", "t.jpg", "image/jpeg", new ByteArrayInputStream(bytes));

        mvc.perform(multipart("/admin/api/photos")
                        .file(file)
                        .param("restaurantKey", "up-r1")
                        .param("productKey", "up-p1")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").exists());
    }
}
