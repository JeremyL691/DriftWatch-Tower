package com.driftwatch.api;

import com.driftwatch.config.RuntimeConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {
    private final RuntimeConfiguration configuration;

    public ConfigurationController(RuntimeConfiguration configuration) { this.configuration = configuration; }

    @GetMapping
    public RuntimeConfiguration.State state() { return configuration.state(); }

    /** Documentation schema; actual JSON decoding additionally rejects coercions and unknown fields. */
    public record SaveRequest(
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED, minimum = "0") long expected_revision,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) com.driftwatch.config.ManagementConfiguration config) {}

    @io.swagger.v3.oas.annotations.Operation(summary = "Save a complete nonsecret configuration revision",
            description = "Admin Basic plus CSRF required. Full replacement with strict types and bounds. Live-safe rules apply after commit; pending restart fields retain startup values until restart.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = SaveRequest.class))))
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Committed saved/effective state"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid fields, types, bounds or relations; no mutation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Admin role and CSRF required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Revision conflict; reload without overwriting dirty inputs")
    })
    @PutMapping
    public RuntimeConfiguration.State save(@RequestBody JsonNode request, Principal principal) {
        return configuration.save(request, principal.getName());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> rejected(org.springframework.web.server.ResponseStatusException exception) {
        return org.springframework.http.ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "error", exception.getStatusCode().value() == 409 ? "revision_conflict" : "invalid_configuration",
                "message", exception.getReason() == null ? "Configuration request rejected" : exception.getReason()));
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return configuration.history(page, size);
    }
}
