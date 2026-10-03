package com.driftwatch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Fails startup when production-style credentials are missing or weak. Only active when
 * {@code driftwatch.security.require-strong-credentials=true} (the selfhost profile), so tests
 * and local development keep working with lightweight credentials.
 *
 * <p>Error messages name the configuration key and never the value.
 */
@Component
public class ProductionCredentialsValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionCredentialsValidator.class);
    private static final int MIN_PASSWORD_LENGTH = 16;
    private static final List<String> FORBIDDEN_PASSWORDS = List.of(
            "driftwatch", "password", "changeme", "change-me", "admin", "secret", "test");

    public ProductionCredentialsValidator(DriftwatchProperties properties) {
        String password = properties.security().admin().password();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("driftwatch.security.admin.password must be set before the application"
                    + " can start; run scripts/selfhost.sh init to generate credentials"
                    + " (local development uses the dev profile)");
        }
        if (!properties.security().requireStrongCredentials()) {
            return;
        }
        String normalized = password.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < MIN_PASSWORD_LENGTH
                || FORBIDDEN_PASSWORDS.contains(normalized)
                || normalized.startsWith("replace-with")) {
            throw new IllegalStateException("driftwatch.security.admin.password is too weak for a self-host deployment;"
                    + " use at least " + MIN_PASSWORD_LENGTH + " characters and avoid example values"
                    + " (run scripts/selfhost.sh init to generate credentials)");
        }
        if (properties.security().ingestTokens().isEmpty()) {
            log.warn("No driftwatch.security.ingest-tokens configured;"
                    + " only the admin account can post events on this instance");
        } else if (properties.security().ingestTokens().stream().anyMatch(token -> token == null || token.length() < 16)) {
            throw new IllegalStateException("each driftwatch.security.ingest-tokens entry must be at least 16 characters"
                    + " for a self-host deployment");
        }
    }
}
