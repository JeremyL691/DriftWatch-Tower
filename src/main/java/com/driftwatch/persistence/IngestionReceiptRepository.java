package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionReceiptRepository
        extends JpaRepository<IngestionReceiptEntity, IngestionReceiptEntity.Key> {
}
