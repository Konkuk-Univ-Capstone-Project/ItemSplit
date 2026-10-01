package com.capstone.itemsplit.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class CommonContractMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void oldSchemaWithData() throws Exception {
        sql("""
            DROP SCHEMA public CASCADE;
            CREATE SCHEMA public;
            CREATE TABLE receipts (id BIGINT PRIMARY KEY, room_id BIGINT NOT NULL, declared_total INTEGER);
            CREATE TABLE items (id BIGINT PRIMARY KEY, receipt_id BIGINT NOT NULL REFERENCES receipts(id),
                                name TEXT NOT NULL, price INTEGER NOT NULL, quantity INTEGER NOT NULL);
            INSERT INTO receipts VALUES (1, 10, 6000);
            INSERT INTO items VALUES (1, 1, '기존 품목', 3000, 2);
            """);
    }

    @Test
    void migratesExistingDataAndCanRunAgain() throws Exception {
        migrate();
        migrate();
        assertThat(value("SELECT name || ':' || price || ':' || quantity || ':' || excluded_from_settlement FROM items WHERE id=1"))
                .isEqualTo("기존 품목:3000:2:false");
        assertThat(value("SELECT declared_total FROM receipts WHERE id=1")).isEqualTo("6000");
        assertThat(value("SELECT pg_typeof(price) FROM items LIMIT 1")).isEqualTo("bigint");
        assertThat(value("SELECT pg_typeof(declared_total) FROM receipts LIMIT 1")).isEqualTo("bigint");
        sql("INSERT INTO items (id, receipt_id, name, price, quantity) VALUES (2,1,'새 품목',1,1)");
        assertThat(value("SELECT excluded_from_settlement FROM items WHERE id=2")).isEqualTo("false");
        assertThatThrownBy(() -> sql("UPDATE items SET excluded_from_settlement=NULL WHERE id=1"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void recoversAfterHibernatePartiallyUpdatedSchema() throws Exception {
        sql("""
            ALTER TABLE items ALTER COLUMN price TYPE BIGINT;
            ALTER TABLE receipts ALTER COLUMN declared_total TYPE BIGINT;
            ALTER TABLE receipts ADD COLUMN request_id VARCHAR(64);
            ALTER TABLE receipts ADD COLUMN request_fingerprint VARCHAR(64);
            ALTER TABLE receipts ADD CONSTRAINT uk_receipt_room_request UNIQUE(room_id, request_id);
            UPDATE receipts SET request_id='existing-request', request_fingerprint='existing-fingerprint';
            """);
        migrate();
        migrate();
        assertThat(value("SELECT excluded_from_settlement FROM items WHERE id=1")).isEqualTo("false");
        assertThat(value("SELECT request_id || ':' || request_fingerprint FROM receipts WHERE id=1"))
                .isEqualTo("existing-request:existing-fingerprint");
        assertThatThrownBy(() -> sql("INSERT INTO receipts VALUES (2,10,1,'existing-request',NULL)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void fillsNullExclusionsButPreservesExplicitExclusions() throws Exception {
        sql("""
            ALTER TABLE items ADD COLUMN excluded_from_settlement BOOLEAN;
            INSERT INTO items VALUES (2,1,'제외 품목',1000,1,TRUE);
            """);
        migrate();
        migrate();
        assertThat(value("SELECT excluded_from_settlement FROM items WHERE id=1")).isEqualTo("false");
        assertThat(value("SELECT excluded_from_settlement FROM items WHERE id=2")).isEqualTo("true");
    }

    @Test
    void preservesLegacyAmountsButRejectsNewInvalidAmounts() throws Exception {
        sql("UPDATE items SET price=0 WHERE id=1; UPDATE receipts SET declared_total=0 WHERE id=1;");
        migrate();
        migrate();
        assertThat(value("SELECT price FROM items WHERE id=1")).isEqualTo("0");
        assertThat(value("SELECT declared_total FROM receipts WHERE id=1")).isEqualTo("0");
        assertThatThrownBy(() -> sql("INSERT INTO items VALUES (2,1,'잘못된 금액',0,1,FALSE)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("INSERT INTO receipts (id,room_id,declared_total) VALUES (2,10,10000001)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rollsBackAllChangesWhenExistingRequestIdsConflict() throws Exception {
        sql("""
            ALTER TABLE receipts ADD COLUMN request_id VARCHAR(64);
            UPDATE receipts SET request_id='duplicate';
            INSERT INTO receipts VALUES (2,10,1000,'duplicate');
            """);
        assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
        assertThat(value("SELECT pg_typeof(price) FROM items LIMIT 1")).isEqualTo("integer");
        assertThat(value("SELECT count(*) FROM information_schema.columns WHERE table_name='items' AND column_name='excluded_from_settlement'"))
                .isEqualTo("0");
    }

    @Test
    void localStartupFailsOnBrokenSchemaAndReadsItemsAfterMigration() throws Exception {
        sql("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        try (var factory = sessionFactory("create")) {
            assertThat(factory.isOpen()).isTrue();
        }
        sql("""
            ALTER TABLE items DROP COLUMN excluded_from_settlement;
            ALTER TABLE items ALTER COLUMN price TYPE INTEGER;
            ALTER TABLE receipts ALTER COLUMN declared_total TYPE INTEGER;
            ALTER TABLE receipts DROP COLUMN request_id;
            ALTER TABLE receipts DROP COLUMN request_fingerprint;
            INSERT INTO users(id,created_at,email,nickname,password)
                VALUES(1,NOW(),'migration@example.invalid','test','unused');
            INSERT INTO rooms(id,created_at,name,owner_id) VALUES(1,NOW(),'test',1);
            INSERT INTO room_members(id,display_name,room_id,user_id) VALUES(1,'test',1,1);
            INSERT INTO receipts(id,created_at,declared_total,name,source_type,payer_member_id,room_id)
                VALUES(1,NOW(),1000,'receipt','MANUAL',1,1);
            INSERT INTO items(id,name,price,quantity,receipt_id) VALUES(1,'item',1000,1,1);
            """);
        assertThatThrownBy(() -> {
            try (var ignored = sessionFactory(null)) { }
        }).hasStackTraceContaining("excluded_from_settlement");
        migrate();
        try (var factory = sessionFactory("validate"); var session = factory.openSession()) {
            var items = session.createQuery("from Item", com.capstone.itemsplit.item.Item.class).getResultList();
            assertThat(items).hasSize(1);
            assertThat(items.getFirst().getPrice()).isEqualTo(1000L);
            assertThat(items.getFirst().isExcludedFromSettlement()).isFalse();
        }
    }

    private org.hibernate.SessionFactory sessionFactory(String mode) throws Exception {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application-local.yml"));
        var properties = java.util.Objects.requireNonNull(yaml.getObject());
        var builder = new org.hibernate.boot.registry.StandardServiceRegistryBuilder();
        properties.forEach((key, value) -> {
            String name = key.toString();
            if (name.startsWith("spring.jpa.properties.")) {
                builder.applySetting(name.substring("spring.jpa.properties.".length()), value);
            }
        });
        var registry = builder
                .applySetting("hibernate.connection.url", postgres.getJdbcUrl())
                .applySetting("hibernate.connection.username", postgres.getUsername())
                .applySetting("hibernate.connection.password", postgres.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", mode == null
                        ? properties.getProperty("spring.jpa.hibernate.ddl-auto") : mode)
                .applySetting("hibernate.physical_naming_strategy",
                        org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy.class)
                .build();
        try {
            var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(jakarta.persistence.Entity.class));
            var sources = new org.hibernate.boot.MetadataSources(registry);
            for (var candidate : scanner.findCandidateComponents("com.capstone.itemsplit")) {
                sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
            }
            return sources.buildMetadata().buildSessionFactory();
        } catch (Exception e) {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
            throw e;
        }
    }

    private void migrate() throws Exception {
        sql(Files.readString(Path.of("db/migrations/001_common_contract.sql")));
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
