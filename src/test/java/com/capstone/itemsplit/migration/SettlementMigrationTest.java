package com.capstone.itemsplit.migration;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers(disabledWithoutDocker=true)
class SettlementMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void v2DatabaseUpgradesWithDataPreservedAndSameRoomCurrentSnapshotConstraint() throws Exception {
        var configuration=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        assertThat(configuration.target("2").load().migrate().migrationsExecuted).isEqualTo(2);
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=connection.createStatement()) {
            sql.execute("INSERT INTO users(id,email,password,nickname,created_at) VALUES(1,'test@test.com','encoded','owner',now())");
            sql.execute("INSERT INTO rooms(id,name,owner_id,created_at) VALUES(1,'first',1,now()),(2,'second',1,now())");
            sql.execute("INSERT INTO room_members(id,room_id,user_id,display_name) VALUES(1,1,1,'original name')");
            var flyway=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try(var row=sql.executeQuery("SELECT display_name FROM room_members WHERE id=1")) {assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("original name");}
            try(var row=sql.executeQuery("SELECT count(*) FROM room_settlement_states")) {row.next();assertThat(row.getInt(1)).isZero();}
            sql.execute("INSERT INTO settlement_snapshots(id,room_id,room_name,version,status,confirmed_by_user_id,confirmed_at,request_id,request_fingerprint,input_fingerprint) VALUES(1,1,'first',1,'CONFIRMED',1,now(),'one','hash','input')");
            assertThatThrownBy(()->sql.execute("INSERT INTO room_settlement_states(room_id,status,current_snapshot_id) VALUES(2,'CONFIRMED',1)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.execute("INSERT INTO room_settlement_states(room_id,status,current_snapshot_id) VALUES(1,'OPEN',1)")).isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO room_settlement_states(room_id,status,current_snapshot_id) VALUES(1,'CONFIRMED',1)");
            assertThatThrownBy(()->sql.execute("INSERT INTO settlement_snapshots(room_id,room_name,version,status,confirmed_by_user_id,confirmed_at,request_id,request_fingerprint,input_fingerprint) VALUES(1,'first',1,'CONFIRMED',1,now(),'two','hash','input')")).isInstanceOf(SQLException.class);
        }
    }
}
