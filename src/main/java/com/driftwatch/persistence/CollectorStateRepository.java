package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectorStateRepository extends JpaRepository<CollectorStateEntity, String> {
}
