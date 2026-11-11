package com.capstone.itemsplit.settlement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class SettlementSnapshotsDisabledTest {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Test void snapshotRoutesAreAbsentByDefaultWhileExistingSettlementRemains() {
        var paths=mappings.getHandlerMethods().keySet().stream().flatMap(m->m.getPatternValues().stream()).toList();
        assertThat(paths).contains("/api/rooms/{roomId}/settlements")
            .doesNotContain("/api/rooms/{roomId}/settlement-state","/api/rooms/{roomId}/settlements/confirmation-preview",
                "/api/rooms/{roomId}/settlement-snapshots","/api/rooms/{roomId}/settlement-snapshots/{snapshotId}","/api/rooms/{roomId}/settlement-snapshots/{snapshotId}/reopen",
                "/api/rooms/{roomId}/settlement-snapshots/{snapshotId}/share-token","/api/shared/settlements/{token}");
    }
}
