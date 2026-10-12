package com.capstone.itemsplit.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class BaselineMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach void emptySchema() throws Exception {
        sql("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
    }

    @Test void createsTheWholeSchemaOnce() throws Exception {
        var flyway=flyway();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(values("SELECT version FROM flyway_schema_history WHERE success")).containsExactly("1");
        assertThat(values("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name <> 'flyway_schema_history'"))
            .containsExactlyInAnyOrder("users","rooms","room_members","room_invite_tokens","room_share_tokens",
                "receipts","items","assignments","receipt_images","ocr_jobs","ocr_job_images","ocr_drafts","ocr_draft_items","ocr_draft_item_members");
        assertThat(values("SELECT convalidated::text FROM pg_constraint WHERE conname IN ('ck_items_money_contract','ck_receipts_declared_total_contract')"))
            .containsExactly("true","true");
    }

    @Test void enforcesMoneyLimitsAndDefaultsFromTheFirstInsert() throws Exception {
        flyway().migrate();seed();
        sql("INSERT INTO items(id,receipt_id,name,price,quantity) VALUES(1,1,'meal',1000000,10)");
        assertThat(values("SELECT excluded_from_settlement::text FROM items WHERE id=1")).containsExactly("false");
        for(String amount:List.of("0,1","1000001,1","1,0","1,1000","1000000,11")) {
            assertThatThrownBy(()->sql("INSERT INTO items(receipt_id,name,price,quantity) VALUES(1,'invalid',"+amount+")"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_items_money_contract");
        }
        for(long total:List.of(0L,10000001L)) {
            assertThatThrownBy(()->sql("UPDATE receipts SET declared_total="+total+" WHERE id=1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_receipts_declared_total_contract");
        }
        sql("UPDATE receipts SET declared_total=10000000 WHERE id=1");
    }

    @Test void retainsCreatorForeignKeyAndImageOrderAndRequestUniqueness() throws Exception {
        flyway().migrate();seed();
        sql("UPDATE receipts SET created_by_user_id=1, request_id='request' WHERE id=1");
        assertThatThrownBy(()->sql("UPDATE receipts SET created_by_user_id=999 WHERE id=1"))
            .isInstanceOf(SQLException.class).hasMessageContaining("fk_receipts_created_by_user");
        assertThatThrownBy(()->sql("INSERT INTO receipts(id,room_id,name,source_type,created_at,request_id) VALUES(2,1,'duplicate','MANUAL',now(),'request')"))
            .isInstanceOf(SQLException.class).hasMessageContaining("uk_receipt_room_request");
        String image="INSERT INTO receipt_images(receipt_id,sort_order,stored_path,content_type,file_size,created_at) VALUES(1,1,'receipts/1/a.png','image/png',10,now())";
        sql(image);
        assertThatThrownBy(()->sql(image)).isInstanceOf(SQLException.class).hasMessageContaining("uk_receipt_images_receipt_order");
    }

    @Test void refusesToSilentlyBaselineAnExistingDatabase() throws Exception {
        sql("CREATE TABLE legacy_data(id BIGINT PRIMARY KEY); INSERT INTO legacy_data VALUES(7)");
        assertThatThrownBy(()->flyway().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("non-empty schema");
        assertThat(values("SELECT id::text FROM legacy_data")).containsExactly("7");
        assertThat(values("SELECT table_name FROM information_schema.tables WHERE table_schema='public'"))
            .containsExactly("legacy_data");
    }

    private Flyway flyway() {
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application.yml"));
        var properties=Objects.requireNonNull(yaml.getObject());
        return Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .baselineOnMigrate(Boolean.parseBoolean(properties.getProperty("spring.flyway.baseline-on-migrate"))).load();
    }
    private void seed() throws SQLException {
        sql("""
            INSERT INTO users(id,email,password,nickname,created_at) VALUES(1,'test@example.com','pw','test',now());
            INSERT INTO rooms(id,name,owner_id,created_at) VALUES(1,'room',1,now());
            INSERT INTO receipts(id,room_id,name,source_type,created_at) VALUES(1,1,'receipt','IMAGE_UPLOAD',now());
            """);
    }
    private Connection connection() throws SQLException {return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());}
    private void sql(String sql) throws SQLException {try(var c=connection();var s=c.createStatement()){s.execute(sql);}}
    private List<String> values(String query) throws SQLException {
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery(query)) {
            List<String> values=new ArrayList<>();while(r.next()) values.add(r.getString(1));return values;
        }
    }
}
