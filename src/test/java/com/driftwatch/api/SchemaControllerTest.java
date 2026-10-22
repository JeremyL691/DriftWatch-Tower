package com.driftwatch.api;

import com.driftwatch.persistence.*;
import com.driftwatch.quality.schema.BaselineOutboxRelay;
import com.driftwatch.quality.schema.SchemaObservationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchemaControllerTest {
    @Test void activationUsesExactLatestOutboxInsteadOfGlobalFiftyRowBacklogAndReportsOnlyPublication() {
        var versions = mock(SchemaVersionRepository.class);
        var service = mock(SchemaObservationService.class);
        var relay = mock(BaselineOutboxRelay.class);
        var outboxes = mock(BaselineOutboxRepository.class);
        var sent = new BaselineOutboxEntity(); sent.setStatus("SENT");
        var controller = new SchemaController(versions, service, relay, outboxes);
        var active = mock(SchemaVersionEntity.class); when(active.getId()).thenReturn(123L); when(active.getStatus()).thenReturn("ACTIVE");
        when(service.activateBaseline(eq("probe"), eq(123L), any(Instant.class))).thenReturn(active);
        when(outboxes.findFirstByEventTypeAndVersionIdOrderByIdDesc("probe", 123L)).thenReturn(Optional.of(sent));
        var result = controller.activateBaseline("probe", Map.of("version_id",123));
        Map<?,?> sync = (Map<?,?>) result.getBody().get("baseline_sync");
        assertThat(sync.get("state")).isEqualTo("PUBLISHED");
        assertThat(sync.get("consumer_application")).isEqualTo("UNCONFIRMED");
        verify(outboxes).findFirstByEventTypeAndVersionIdOrderByIdDesc("probe",123L);
        verifyNoInteractions(relay);
    }

    @Test void pendingPublishNeverClaimsConsumerNonApplication() {
        var versions = mock(SchemaVersionRepository.class);
        var service = mock(SchemaObservationService.class);
        var relay = mock(BaselineOutboxRelay.class);
        var outboxes = mock(BaselineOutboxRepository.class);
        var active = mock(SchemaVersionEntity.class); when(active.getId()).thenReturn(123L); when(active.getStatus()).thenReturn("ACTIVE");
        when(service.activateBaseline(eq("probe"), eq(123L), any(Instant.class))).thenReturn(active);
        var pending = mock(BaselineOutboxEntity.class); when(pending.getId()).thenReturn(456L); when(pending.getStatus()).thenReturn("PENDING");
        when(outboxes.findFirstByEventTypeAndVersionIdOrderByIdDesc("probe",123L)).thenReturn(Optional.of(pending));
        var result = new SchemaController(versions,service,relay,outboxes).activateBaseline("probe",Map.of("version_id",123));
        Map<?,?> sync = (Map<?,?>) result.getBody().get("baseline_sync");
        assertThat(sync.get("state")).isEqualTo("PENDING");
        assertThat(sync.get("consumer_application")).isEqualTo("UNCONFIRMED");
        assertThat(sync.get("detail").toString()).doesNotContain("has not applied");
        verify(relay).publishNow(456L);
    }

    @Test
    void baselineStatusReportsAppliedLocalWhenStreamsStoreMatchesActiveVersion() {
        var versions = mock(SchemaVersionRepository.class);
        var service = mock(SchemaObservationService.class);
        var relay = mock(BaselineOutboxRelay.class);
        var outboxes = mock(BaselineOutboxRepository.class);
        var inspector = mock(com.driftwatch.quality.schema.StreamsBaselineStatus.class);

        var active = mock(SchemaVersionEntity.class);
        when(active.getId()).thenReturn(123L);
        when(versions.findFirstByEventTypeAndStatus("probe", "ACTIVE")).thenReturn(Optional.of(active));

        var sent = mock(BaselineOutboxEntity.class);
        when(sent.getStatus()).thenReturn("SENT");
        when(sent.getId()).thenReturn(789L);
        when(outboxes.findFirstByEventTypeAndVersionIdOrderByIdDesc("probe", 123L)).thenReturn(Optional.of(sent));

        when(inspector.check("probe", 123L)).thenReturn(
                new com.driftwatch.quality.schema.StreamsBaselineStatus.CheckResult("APPLIED_LOCAL", 123L, "Matches local Streams store"));

        var controller = new SchemaController(versions, service, relay, outboxes, inspector);
        var response = controller.baselineStatus("probe");
        Map<?, ?> sync = (Map<?, ?>) response.getBody().get("baseline_sync");
        assertThat(sync.get("state")).isEqualTo("PUBLISHED");
        assertThat(sync.get("consumer_application")).isEqualTo("APPLIED_LOCAL");
        assertThat(sync.get("consumer_applied_version_id")).isEqualTo(123L);
        assertThat(sync.get("consumer_detail")).isEqualTo("Matches local Streams store");
    }
}
