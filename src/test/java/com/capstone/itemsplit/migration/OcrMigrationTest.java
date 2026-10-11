package com.capstone.itemsplit.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class OcrMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void upgradesPr23V3WithoutRecreatingImagesOrInventingAnUploader() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("2").load().migrate();
        try(var connection=connection();var sql=connection.createStatement()) {
            sql.execute("""
                INSERT INTO users(id,email,password,nickname,created_at) VALUES(1,'old@example.com','pw','old',now());
                INSERT INTO rooms(id,name,owner_id,created_at) VALUES(1,'room',1,now());
                INSERT INTO receipts(id,room_id,name,source_type,stored_path,original_filename,created_at)
                  VALUES(1,1,'old receipt','IMAGE_UPLOAD','receipts/old.jpg','old.jpg',now());
                INSERT INTO receipts(id,room_id,name,source_type,stored_path,content_type,created_at)
                  VALUES(2,1,'long MIME','IMAGE_UPLOAD','receipts/other.jpg','image/jpeg;legacy-metadata=' || repeat('x',100),now());
                """);
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("3").load().migrate();
        var flyway=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=connection();var sql=connection.createStatement()) {
            try(var result=sql.executeQuery("SELECT i.stored_path,i.content_type,i.file_size,r.created_by_user_id FROM receipt_images i JOIN receipts r ON r.id=i.receipt_id WHERE r.id=1")) {
                assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("receipts/old.jpg");
                assertThat(result.getString(2)).isEqualTo("application/octet-stream");assertThat(result.getLong(3)).isZero();
                assertThat(result.getObject(4)).isNull();assertThat(result.next()).isFalse();
            }
            try(var result=sql.executeQuery("SELECT content_type FROM receipt_images WHERE receipt_id=2")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(("image/jpeg;legacy-metadata="+"x".repeat(100)).substring(0,50));
            }
            sql.execute("""
                INSERT INTO ocr_jobs(id,receipt_id,request_id,status,provider,requested_by_user_id,created_at)
                    VALUES(1,1,'test','PENDING','FAKE_CLOVA_RECEIPT',1,now());
                INSERT INTO ocr_drafts(id,job_id,receipt_id,status,created_at,updated_at) VALUES(1,1,1,'EDITING',now(),now());
                INSERT INTO ocr_job_images(job_id,image_id,sort_order,status) SELECT 1,id,1,'PENDING' FROM receipt_images WHERE receipt_id=1;
                INSERT INTO ocr_draft_items(draft_id,image_id,row_index,sort_order) SELECT 1,id,1,1 FROM receipt_images WHERE receipt_id=1;
                DELETE FROM receipt_images WHERE receipt_id IN (1,2);
                DELETE FROM receipts WHERE id IN (1,2);
                """);
            for(String table:java.util.List.of("receipt_images","ocr_jobs","ocr_job_images","ocr_drafts","ocr_draft_items")) {
                try(var result=sql.executeQuery("SELECT count(*) FROM "+table)) {result.next();assertThat(result.getInt(1)).as(table).isZero();}
            }
        }
    }
    private Connection connection() throws SQLException{return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());}
}
