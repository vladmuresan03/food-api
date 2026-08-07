package com.foodfinder.csv;

import com.foodfinder.IntegrationTest;
import com.foodfinder.admin.CsvImportLogRepository;
import com.foodfinder.admin.TestDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
}
