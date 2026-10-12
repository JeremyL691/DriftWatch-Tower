package com.driftwatch.quality.schema;

import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FAILED baseline rows are re-driven unless a newer row of the same type supersedes them. */
class BaselineOutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BaselineMessage> kafka = mock(KafkaTemplate.class);
    private final BaselineOutboxRepository repository = mock(BaselineOutboxRepository.class);
    private final BaselineOutboxRelay relay = new BaselineOutboxRelay(repository, kafka);

    private BaselineOutboxEntity failedRow(long id, String eventType) {
        BaselineOutboxEntity row = new BaselineOutboxEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
        row.setEventType(eventType);
        row.setVersionId(id);
        row.setStatus(BaselineOutboxEntity.STATUS_FAILED);
        row.setAttempts(10);
        return row;
    }

    @Test
    void failedRowIsRetriedAndMarkedSentOnceTheBrokerAcknowledges() {
        BaselineOutboxEntity row = failedRow(7L, "orders");
        when(repository.findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_FAILED)).thenReturn(List.of(row));
        when(repository.existsByEventTypeAndIdGreaterThan("orders", 7L)).thenReturn(false);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        relay.retryFailed();

        assertThat(row.getStatus()).isEqualTo(BaselineOutboxEntity.STATUS_SENT);
        verify(repository).save(row);
    }

    @Test
    void supersededFailedRowIsNeverRepublished() {
        BaselineOutboxEntity row = failedRow(7L, "orders");
        when(repository.findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_FAILED)).thenReturn(List.of(row));
        when(repository.existsByEventTypeAndIdGreaterThan("orders", 7L)).thenReturn(true);

        relay.retryFailed();

        verify(kafka, never()).send(anyString(), eq("orders"), any());
        assertThat(row.getStatus()).isEqualTo(BaselineOutboxEntity.STATUS_FAILED);
    }
}
