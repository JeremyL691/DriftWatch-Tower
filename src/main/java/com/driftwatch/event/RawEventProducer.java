package com.driftwatch.event;

import com.driftwatch.config.KafkaTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RawEventProducer {

    private final KafkaTemplate<String, DataEvent> kafkaTemplate;

    public RawEventProducer(KafkaTemplate<String, DataEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DataEvent event) {
        kafkaTemplate.send(KafkaTopics.RAW_EVENTS, partitionKey(event), event);
    }

    static String partitionKey(DataEvent event) {
        return event.source() + "|" + event.eventType();
    }
}
