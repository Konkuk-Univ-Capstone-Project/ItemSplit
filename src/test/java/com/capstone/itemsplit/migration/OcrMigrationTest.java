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
    @Test void baselineCreatesOcrRelationshipsAndCleansChildrenOnDeletion() throws Exception {
        var flyway=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=connection();var sql=connection.createStatement()) {
            sql.execute("""
                INSERT INTO users(id,email,password,nickname,created_at) VALUES(1,'test@example.com','pw','test',now());
                INSERT INTO rooms(id,name,owner_id,created_at) VALUES(1,'room',1,now());
                INSERT INTO receipts(id,room_id,name,source_type,created_by_user_id,created_at)
                  VALUES(1,1,'receipt','IMAGE_UPLOAD',1,now());
                INSERT INTO receipt_images(receipt_id,sort_order,stored_path,content_type,file_size,created_at)
                  VALUES(1,1,'receipts/1/a.png','image/png',10,now());
                """);
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
