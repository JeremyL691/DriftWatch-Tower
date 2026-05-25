package com.driftwatch.event;

import com.driftwatch.config.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RawEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RawEventConsumer.class);

    private final RawEventService service;

    public RawEventConsumer(RawEventService service) {
        this.service = service;
    }

    @KafkaListener(topics = KafkaTopics.RAW_EVENTS, groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(DataEvent event) {
        boolean persisted = service.ingestIfAbsent(event);
        if (!persisted) {
            log.debug("Skipped duplicate Kafka delivery for event_id={}", event.eventId());
        }
    }
}
