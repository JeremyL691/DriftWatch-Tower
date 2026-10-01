package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SourceInboxRepository extends JpaRepository<SourceInboxEntity, Long> {

    Optional<SourceInboxEntity> findBySourceAndGithubEventId(String source, String githubEventId);

    boolean existsBySourceAndGithubEventId(String source, String githubEventId);

    List<SourceInboxEntity> findBySourceOrderByCreatedAtDescGithubEventIdDesc(String source);

    long countBySource(String source);

    List<SourceInboxEntity> findByReceivedAtBefore(Instant cutoff);
}
