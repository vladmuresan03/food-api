package com.foodfinder.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean
    public FileStorage fileStorage(@Value("${foodfinder.storage-dir:./data/storage}") String dir) {
        return new LocalFileStorage(dir);
    }
}
