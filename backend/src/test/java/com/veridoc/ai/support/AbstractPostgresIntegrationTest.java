package com.veridoc.ai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.veridoc.ai.VeriDocApplication;

import org.assertj.core.api.Assertions;

/**
 * Integration-test base class.
 *
 * <p>Spins up a real PostgreSQL with the pgvector extension so that migrations,
 * the vector index, HNSW ordering and native retrieval SQL are all exercised for
 * real. A mock or in-memory substitute would not catch any of the things most
 * likely to break in this system.
 *
 * <p>Gemini is never contacted: the tests that need generation stub the
 * chat/embedding clients explicitly.
 */
@SpringBootTest(classes = VeriDocApplication.class)
@Testcontainers
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
public abstract class AbstractPostgresIntegrationTest {

    /**
     * {@code pgvector/pgvector} ships the same PostgreSQL as the official image
     * with the vector extension available, which the application requires.
     */
    protected static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17");

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("veridoc")
            .withUsername("veridoc")
            .withPassword("veridoc");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // A placeholder key so the AI auto-configuration can build its client
        // beans. No test in this suite calls Gemini; tests that need generation
        // replace the chat/embedding models explicitly.
        registry.add("spring.ai.google.genai.api-key", () -> "test-key-not-used");
        registry.add("spring.ai.google.genai.embedding.api-key", () -> "test-key-not-used");
        registry.add("veridoc.rate-limit.enabled", () -> "false");
    }

    /** Guard: the migration must have created pgvector, not merely a plain schema. */
    protected static void assertPgVectorInstalled(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Boolean installed = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')",
                Boolean.class);
        Assertions.assertThat(installed)
                .as("pgvector extension must be installed by V1__init.sql")
                .isTrue();
    }
}