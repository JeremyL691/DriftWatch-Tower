package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedReceiptRepository extends JpaRepository<ProcessedReceiptEntity, String> {
}
