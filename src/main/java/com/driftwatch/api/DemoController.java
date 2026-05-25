package com.driftwatch.api;

import com.driftwatch.demo.DemoScenarioService;
import com.driftwatch.demo.DemoScenarioService.ScenarioRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final DemoScenarioService scenarios;

    public DemoController(DemoScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @PostMapping("/run-scenario/{scenario}")
    public ResponseEntity<ScenarioRun> run(@PathVariable String scenario) {
        return switch (scenario) {
            case "normal-flow"      -> ResponseEntity.accepted().body(scenarios.runNormalFlow());
            case "duplicate-events" -> ResponseEntity.accepted().body(scenarios.runDuplicateEvents());
            case "late-events"      -> ResponseEntity.accepted().body(scenarios.runLateEvents());
            case "null-spike"       -> ResponseEntity.accepted().body(scenarios.runNullSpike());
            case "anomaly-spike"    -> ResponseEntity.accepted().body(scenarios.runAnomalySpike());
            case "stale-source"     -> ResponseEntity.accepted().body(scenarios.runStaleSource());
            case "mixed-incident"   -> ResponseEntity.accepted().body(scenarios.runMixedIncident());
            case "schema-drift"     -> ResponseEntity.accepted().body(scenarios.runSchemaDrift());
            default -> ResponseEntity.badRequest().build();
        };
    }
}
