package com.driftwatch.quality;

/** Rule version recorded on every ProcessedEvent and in emitted evidence. */
public final class RuleVersions {

    /** Bump when detector semantics change so evidence stays interpretable. */
    public static final String RULES_VERSION = "rules-2026.10-2";

    private RuleVersions() {
    }
}