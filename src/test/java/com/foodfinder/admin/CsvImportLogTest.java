package com.foodfinder.admin;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CsvImportLogTest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired CsvImportLogRepository importLog;
    @Autowired TestDataCleanup cleanup;

    @AfterEach
    void cleanup() {
        cleanup.wipeCatalogData();
    }

    @Test
    void successfulImportCreatesLogRow() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "good.csv", "text/csv", """
                        restaurant_key,name,city,status
                        log-r1,Log R1,Cluj-Napoca,ACTIVE
                        log-r2,Log R2,Cluj-Napoca,ACTIVE
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        CsvImportLog log = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertThat(log.getSlug()).isEqualTo("restaurants");
        assertThat(log.getFilename()).isEqualTo("good.csv");
        assertThat(log.getActor()).isEqualTo("test-admin");
        assertThat(log.isDryRun()).isFalse();
        assertThat(log.getTotalRows()).isEqualTo(2);
        assertThat(log.getStatus()).isEqualTo(CsvImportLog.Status.OK);
    }

    @Test
    void dryRunIsRecordedAsDryRun() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "dr.csv", "text/csv", """
                        restaurant_key,name,city,status
                        dr-r1,DR,Cluj-Napoca,ACTIVE
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "true")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        CsvImportLog log = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertThat(log.isDryRun()).isTrue();
        assertThat(log.getStatus()).isEqualTo(CsvImportLog.Status.OK);
    }

    @Test
    void failedImportIsRecordedAsFailed() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", """
                        restaurant_key,name,city,status
                        good-r,Good,Cluj-Napoca,ACTIVE
                        bad-r,Bad,Cluj-Napoca,BOGUS
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        CsvImportLog log = importLog.findAllByOrderByStartedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertThat(log.getStatus()).isEqualTo(CsvImportLog.Status.OK);
        assertThat(log.getErrorCount()).isEqualTo(1);
    }

    @Test
    void importHistoryPageRendersRows() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", """
                        restaurant_key,name,city,status
                        hist-r,Hist,Cluj-Napoca,ACTIVE
                        """.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/csv/restaurants")
                        .file(file)
                        .param("dryRun", "false")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/admin/imports")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .xpath("//code[text()='restaurants']").exists());
    }
}
