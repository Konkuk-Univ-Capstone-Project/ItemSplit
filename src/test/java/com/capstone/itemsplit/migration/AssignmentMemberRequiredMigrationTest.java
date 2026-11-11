package com.capstone.itemsplit.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V3 makes assignments.room_member_id required and refuses to run over rows it cannot keep. */
@Testcontainers(disabledWithoutDocker = true)
class AssignmentMemberRequiredMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void startFromV2() {
        Flyway clean = flyway(null);
        clean.clean();
        assertThat(flyway("2").migrate().migrationsExecuted).isEqualTo(2);
        execute("""
            INSERT INTO users(id,email,password,nickname,created_at) VALUES(1,'owner@example.com','pw','owner',now());
            INSERT INTO rooms(id,name,owner_id,created_at) VALUES(1,'room',1,now()),(2,'other room',1,now());
            INSERT INTO room_members(id,room_id,user_id,display_name) VALUES(1,1,1,'owner');
            INSERT INTO room_members(id,room_id,display_name) VALUES(2,1,'manual'),(3,2,'elsewhere');
            INSERT INTO receipts(id,room_id,name,source_type,created_by_user_id,created_at)
                VALUES(1,1,'receipt','MANUAL',1,now());
            INSERT INTO items(id,receipt_id,name,price,quantity) VALUES(1,1,'assigned',1000,1),(2,1,'unassigned',500,2);
            INSERT INTO assignments(item_id,room_member_id) VALUES(1,1),(1,2);
            """);
    }

    @Test
    void cleanDataMigratesAndTheColumnRejectsNull() {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);

        assertThat(memberColumnNullable()).isFalse();
        assertThat(count("SELECT count(*) FROM assignments")).isEqualTo(2);
        // An item without participants is simply an item without assignment rows.
        assertThat(count("SELECT count(*) FROM assignments WHERE item_id = 2")).isZero();
        assertThatThrownBy(() -> execute("INSERT INTO assignments(item_id,room_member_id) VALUES(2,NULL)"))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(SQLException.class);
        execute("INSERT INTO assignments(item_id,room_member_id) VALUES(2,2)");
    }

    @Test
    void assignmentWithoutMemberStopsTheMigrationUntilRepaired() {
        execute("INSERT INTO assignments(item_id,room_member_id) VALUES(2,NULL)");

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("1 assignment(s) without a room member, 0 assignment(s) whose member belongs to another room");
        assertStillAtV2();
        assertThat(count("SELECT count(*) FROM assignments")).as("nothing deleted").isEqualTo(3);

        execute("DELETE FROM assignments WHERE room_member_id IS NULL");
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(memberColumnNullable()).isFalse();
    }

    @Test
    void memberFromAnotherRoomStopsTheMigration() {
        execute("INSERT INTO assignments(item_id,room_member_id) VALUES(2,3)");

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("0 assignment(s) without a room member, 1 assignment(s) whose member belongs to another room");
        assertStillAtV2();

        execute("UPDATE assignments SET room_member_id = 2 WHERE room_member_id = 3");
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);
    }

    private void assertStillAtV2() {
        assertThat(flyway(null).info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version IN ('3','4')")).isZero();
        assertThat(memberColumnNullable()).isTrue();
    }

    private boolean memberColumnNullable() {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'assignments' "
            + "AND column_name = 'room_member_id' AND is_nullable = 'YES'") == 1;
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().cleanDisabled(false)
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private void execute(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
