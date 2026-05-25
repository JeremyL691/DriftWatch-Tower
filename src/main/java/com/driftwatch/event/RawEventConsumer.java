package com.driftwatch.event;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.quality.QualityProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RawEventConsumer {

    private final QualityProcessor processor;

    public RawEventConsumer(QualityProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = KafkaTopics.RAW_EVENTS, groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(DataEvent event) {
        processor.process(event);
    }
}
