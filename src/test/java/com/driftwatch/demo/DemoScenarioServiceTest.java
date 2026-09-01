package com.driftwatch.demo;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEventProducer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DemoScenarioServiceTest {

    @Test
    void nullSpikeEventsStayInOneEventTimeWindow() {
        RawEventProducer producer = mock(RawEventProducer.class);
        DemoScenarioService service = new DemoScenarioService(producer);
        ArgumentCaptor<DataEvent> events = ArgumentCaptor.forClass(DataEvent.class);

        service.runNullSpike();

        verify(producer, times(6)).publish(events.capture());
        assertThat(eventTimeMinutes(events.getAllValues())).hasSize(1);
    }

    @Test
    void anomalyScenarioUsesTwoBaselineWindowsAndOneBurstWindow() {
        RawEventProducer producer = mock(RawEventProducer.class);
        DemoScenarioService service = new DemoScenarioService(producer);
        ArgumentCaptor<DataEvent> events = ArgumentCaptor.forClass(DataEvent.class);

        service.runAnomalySpike();

        verify(producer, times(12)).publish(events.capture());
        Map<Long, Long> countsByWindow = events.getAllValues().stream()
                .collect(Collectors.groupingBy(
                        event -> event.eventTimestamp().getEpochSecond() / 60,
                        Collectors.counting()
                ));
        assertThat(countsByWindow.values()).containsExactlyInAnyOrder(2L, 2L, 8L);
    }

    private List<Long> eventTimeMinutes(List<DataEvent> events) {
        return events.stream()
                .map(event -> event.eventTimestamp().getEpochSecond() / 60)
                .distinct()
                .toList();
    }
}
