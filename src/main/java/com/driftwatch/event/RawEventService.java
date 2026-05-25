package com.driftwatch.event;

import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RawEventService {

    public static final String STATUS_PENDING = "PENDING";

    private final RawEventRepository repository;
    private final PayloadHasher hasher;
    private final ObjectMapper objectMapper;

    public RawEventService(RawEventRepository repository, PayloadHasher hasher, ObjectMapper objectMapper) {
        this.repository = repository;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RawEventEntity ingest(DataEvent event) {
        RawEventEntity entity = new RawEventEntity();
        entity.setEventId(event.eventId());
        entity.setSource(event.source());
        entity.setEventType(event.eventType());
        entity.setEventTimestamp(event.eventTimestamp());
        entity.setReceivedAt(Instant.now());
        entity.setPayloadJson(objectMapper.valueToTree(event.payload()));
        entity.setPayloadHash(hasher.hash(event.payload()));
        entity.setQualityStatus(STATUS_PENDING);
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Page<RawEventEntity> recent(int page, int size) {
        return repository.findAllByOrderByReceivedAtDesc(PageRequest.of(page, size));
    }
}
