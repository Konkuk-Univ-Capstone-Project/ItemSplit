package com.capstone.itemsplit.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the application with its real settings against an empty PostgreSQL database. */
@SpringBootTest(properties = "app.storage.local.root-path=./build/test-storage")
@Testcontainers(disabledWithoutDocker = true)
class FlywayStartupTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void emptyDatabaseIsCreatedByMigrationsAndPassesSchemaValidation() {
        // The context only loads if Hibernate's validate accepted the schema Flyway created.
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .startsWith("1", "2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'items' AND column_name = 'excluded_from_settlement'", String.class))
                .isEqualTo("false");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_items_money_contract', 'ck_receipts_declared_total_contract')", Integer.class))
                .isEqualTo(2);
    }
}
