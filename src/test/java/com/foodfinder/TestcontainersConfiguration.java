package com.foodfinder;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spins up a real PostgreSQL 16 container for the test suite and binds it as
 * the application's primary DataSource via Spring Boot's
 * {@code @ServiceConnection} support. Replaces the local-PostgreSQL
 * dependency that the project carried before; the existing CI service
 * container is no longer needed and tests can be run on any machine with
 * Docker.
 *
 * <p>To skip Testcontainers (e.g. on a developer machine without Docker
 * available, or in an environment that already provides a PostgreSQL
 * server), set the JVM system property
 * {@code -Dfoodfinder.tests.database=skip}. When skipped, the regular
 * {@code application.yaml} is used and tests fall back to the local
 * PostgreSQL server.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("foodfinder_test")
                .withUsername("foodfinder")
                .withPassword("foodfinder")
                .withReuse(true);
    }
}
