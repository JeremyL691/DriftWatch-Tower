package com.driftwatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourcePollRunRepository extends JpaRepository<SourcePollRunEntity, Long> {

    List<SourcePollRunEntity> findTop20BySourceOrderByIdDesc(String source);
}
