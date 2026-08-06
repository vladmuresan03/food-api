package com.foodfinder;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that pulls in the standard {@link SpringBootTest}
 * bootstrap plus the project's {@link TestcontainersConfiguration}.
 * Use this on every test class that needs the database so the same
 * PostgreSQL container is reused across the suite and the test
 * author doesn't have to remember to add the import.
 *
 * <p>Tests that need extra configuration (a different profile,
 * MockMvc auto-configure, etc.) can still use the bare
 * {@link SpringBootTest} annotation and add
 * {@code @Import(TestcontainersConfiguration.class)} explicitly.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
