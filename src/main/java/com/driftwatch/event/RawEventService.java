package com.driftwatch.event;

import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RawEventService {

    private final RawEventRepository repository;

    public RawEventService(RawEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<RawEventEntity> recent(int page, int size) {
        return repository.findAllByOrderByReceivedAtDesc(PageRequest.of(page, size));
    }
}
