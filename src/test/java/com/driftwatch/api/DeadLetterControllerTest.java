package com.driftwatch.api;

import com.driftwatch.dlt.DeadLetterService;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replay status mapping (guide 7.1): a published replay is 202, a replay the broker did not acknowledge is
 * 503 so the caller knows the record is still unrecovered. Only the broker outcome decides the code.
 */
class DeadLetterControllerTest {

    @Test
    void publishedReplayIsAccepted() {
        DeadLetterService service = mock(DeadLetterService.class);
        when(service.get(7L)).thenReturn(new DeadLetterRecordEntity());
        when(service.replay(7L)).thenReturn(new DeadLetterService.ReplayResult("attempt-1", "SINK", "PUBLISHED", "ok"));

        ResponseEntity<Map<String, Object>> response = new DeadLetterController(service).replay(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("outcome", "PUBLISHED");
    }

    @Test
    void replayTheBrokerDidNotAcknowledgeIsServiceUnavailable() {
        DeadLetterService service = mock(DeadLetterService.class);
        when(service.get(7L)).thenReturn(new DeadLetterRecordEntity());
        when(service.replay(7L)).thenReturn(new DeadLetterService.ReplayResult("attempt-2", "STREAM", "FAILED",
                "broker did not acknowledge the replay within the timeout"));

        ResponseEntity<Map<String, Object>> response = new DeadLetterController(service).replay(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("outcome", "FAILED");
    }

    @Test
    void unknownDeadLetterIsNotFoundAndNeverReplayed() {
        DeadLetterService service = mock(DeadLetterService.class);
        when(service.get(anyLong())).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = new DeadLetterController(service).replay(404L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).replay(anyLong());
    }
}
