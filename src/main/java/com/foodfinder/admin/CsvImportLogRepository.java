package com.foodfinder.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CsvImportLogRepository extends JpaRepository<CsvImportLog, Long> {

    List<CsvImportLog> findAllByOrderByStartedAtDesc(Pageable pageable);
}
