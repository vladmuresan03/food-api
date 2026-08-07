package com.foodfinder.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per CSV import attempt. Created when the import starts and
 * updated with the final counts when the run finishes (success or fail).
 * The principal name (admin user) is recorded for audit.
 */
@Entity
@Table(name = "csv_import_log")
public class CsvImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String slug;

    @Column(length = 255)
    private String filename;

    @Column(nullable = false, length = 120)
    private String actor;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "inserted_rows", nullable = false)
    private int insertedRows;

    @Column(name = "updated_rows", nullable = false)
    private int updatedRows;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OK;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt = Instant.now();

    public enum Status { OK, FAILED }

    public static CsvImportLog start(String slug, String filename, String actor, boolean dryRun) {
        CsvImportLog log = new CsvImportLog();
        log.slug = slug;
        log.filename = filename;
        log.actor = actor;
        log.dryRun = dryRun;
        log.startedAt = Instant.now();
        log.finishedAt = log.startedAt;
        return log;
    }

    public void finishOk(int totalRows, int insertedRows, int updatedRows, int errorCount) {
        this.totalRows = totalRows;
        this.insertedRows = insertedRows;
        this.updatedRows = updatedRows;
        this.errorCount = errorCount;
        this.status = Status.OK;
        this.finishedAt = Instant.now();
    }

    public void finishFailed(String message) {
        this.status = Status.FAILED;
        this.failureMessage = message;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getFilename() { return filename; }
    public String getActor() { return actor; }
    public boolean isDryRun() { return dryRun; }
    public int getTotalRows() { return totalRows; }
    public int getInsertedRows() { return insertedRows; }
    public int getUpdatedRows() { return updatedRows; }
    public int getErrorCount() { return errorCount; }
    public Status getStatus() { return status; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public long durationMs() {
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
