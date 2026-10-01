package com.driftwatch.api;

import com.driftwatch.operations.RetentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/**
 * Operations surface (execution guide, sections 7.3 and 7.4): retention settings, the state
 * counters that must stay visible, and a manual pass for drills. Reads are pure.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

    private final RetentionService retentionService;
    private final Clock clock;

    public OperationsController(RetentionService retentionService, Clock clock) {
        this.retentionService = retentionService;
        this.clock = clock;
    }

    @GetMapping("/retention")
    public Map<String, Object> retention() {
        return retentionService.status();
    }

    @PostMapping("/retention/run")
    public Map<String, Object> runRetention() {
        Map<String, Long> pruned = retentionService.runNow(clock.instant());
        return Map.of("status", "completed", "pruned", pruned);
    }
}
