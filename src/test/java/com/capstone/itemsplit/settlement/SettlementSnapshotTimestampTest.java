package com.capstone.itemsplit.settlement;
import com.capstone.itemsplit.room.Room;
import com.capstone.itemsplit.user.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
class SettlementSnapshotTimestampTest {
    @Test void confirmationTimestampMatchesDatabaseMicrosecondPrecisionBeforeFirstResponse() {
        LocalDateTime precise=LocalDateTime.of(2026,11,11,12,0,0,123456789);
        LocalDateTime persisted=precise.withNano(123456000);
        Room room=Room.create("room",User.create("time@test.com","encoded","owner"));
        try(var clock=Mockito.mockStatic(LocalDateTime.class,Mockito.CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(precise);
            var snapshot=SettlementSnapshot.create(room,1L,1L,"request","fingerprint","input",List.of(),List.of());
            assertThat(snapshot.getConfirmedAt()).isEqualTo(persisted);
        }
    }
}
