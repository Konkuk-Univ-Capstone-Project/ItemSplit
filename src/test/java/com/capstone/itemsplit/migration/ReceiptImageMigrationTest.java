package com.capstone.itemsplit.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** V3 on a database that already holds receipts uploaded with the old single-image columns. */
@Testcontainers(disabledWithoutDocker = true)
class ReceiptImageMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void schemaBeforeReceiptImages() throws Exception {
        sql("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        flyway("2").migrate();
        sql("""
            INSERT INTO users(id, created_at, email, nickname, password)
                VALUES (1, NOW(), 'migration@example.invalid', 'test', 'unused');
            INSERT INTO rooms(id, created_at, name, owner_id) VALUES (1, NOW(), 'room', 1);
            INSERT INTO receipts(id, created_at, name, source_type, room_id, stored_path, original_filename, content_type, file_size)
                VALUES (1, TIMESTAMP '2026-06-01 12:00:00', 'with image', 'IMAGE_UPLOAD', 1,
                        'receipts/1/abc.png', 'photo.png', 'image/png', 2048);
            INSERT INTO receipts(id, created_at, name, source_type, room_id, stored_path)
                VALUES (2, NOW(), 'image without metadata', 'IMAGE_UPLOAD', 1, 'receipts/1/def');
            INSERT INTO receipts(id, created_at, name, source_type, room_id) VALUES (3, NOW(), 'manual', 'MANUAL', 1);
            """);
    }

    @Test
    void copiesExistingImagesAsTheFirstImageOfTheirReceipt() throws Exception {
        flyway(null).migrate();

        assertThat(value("SELECT count(*) FROM receipt_images")).isEqualTo("2");
        assertThat(value("""
            SELECT sort_order || '|' || stored_path || '|' || original_filename || '|' || content_type || '|' || file_size
                   || '|' || created_at
            FROM receipt_images WHERE receipt_id = 1"""))
            .isEqualTo("1|receipts/1/abc.png|photo.png|image/png|2048|2026-06-01 12:00:00");
        assertThat(value("SELECT content_type || '|' || file_size FROM receipt_images WHERE receipt_id = 2"))
            .isEqualTo("application/octet-stream|0");
        assertThat(value("SELECT count(*) FROM receipt_images WHERE receipt_id = 3")).isEqualTo("0");
    }

    @Test
    void keepsExistingReceiptsWithoutACreatorAndLeavesTheOldColumns() throws Exception {
        flyway(null).migrate();

        assertThat(value("SELECT count(*) FROM receipts WHERE created_by_user_id IS NULL")).isEqualTo("3");
        assertThat(value("SELECT stored_path FROM receipts WHERE id = 1")).isEqualTo("receipts/1/abc.png");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        return (target == null ? configuration : configuration.target(target)).load();
    }

    private void sql(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String value(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1).toString();
        }
    }
}
