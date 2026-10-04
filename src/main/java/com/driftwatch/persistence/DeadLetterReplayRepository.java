package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterReplayRepository extends JpaRepository<DeadLetterReplayEntity, Long> {

    List<DeadLetterReplayEntity> findByDeadLetterIdOrderByIdAsc(Long deadLetterId);
}
