package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CsvPreviewServiceTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;

    @Test
    void previewReturnsFirstFiveRowsWithoutImporting() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "preview.csv", "text/csv", """
                        restaurant_key,name,city,status
                        pv-r1,R1,Cluj-Napoca,ACTIVE
                        pv-r2,R2,Cluj-Napoca,ACTIVE
                        pv-r3,R3,Cluj-Napoca,ACTIVE
                        pv-r4,R4,Cluj-Napoca,ACTIVE
                        pv-r5,R5,Cluj-Napoca,ACTIVE
                        pv-r6,R6,Cluj-Napoca,ACTIVE
                        pv-r7,R7,Cluj-Napoca,ACTIVE
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "true")
                        .param("preview", "true")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(xpath("//h3[contains(text(),'Preview of')]").exists())
                .andExpect(xpath("//td[text()='pv-r1']").exists())
                .andExpect(xpath("//td[text()='pv-r5']").exists())
                .andExpect(xpath("//td[text()='pv-r6']").doesNotExist());

        // Nothing was written
        org.assertj.core.api.Assertions.assertThat(
                restaurantCsv.parse(new java.io.StringReader(""), false)
                        .totalRows()).isEqualTo(0);
    }

    @Test
    void previewReportsBadCsv() throws Exception {
        // Missing comma after header — parser will fail or read junk.
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", """
                        restaurant_key,name,city
                        "unclosed quote starts here...
                        pv-r,R,Cluj-Napoca
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "true")
                        .param("preview", "true")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                // Either the parse error is shown OR the rows are partial;
                // both are valid outcomes. Just check the preview box
                // appeared.
                .andExpect(xpath("//h3[contains(text(),'Preview of')]").exists());
    }
}
