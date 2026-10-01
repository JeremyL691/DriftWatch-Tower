package com.driftwatch.stream;

/** Running null-spike totals for one (scope, field, window); {@code fired} keeps it to one alert. */
record NullWindowState(long windowStart, double total, double nulls, boolean fired) {

    NullWindowState incremented(boolean nullish) {
        return new NullWindowState(windowStart, total + 1, nulls + (nullish ? 1 : 0), fired);
    }

    NullWindowState firedOnce() {
        return new NullWindowState(windowStart, total, nulls, true);
    }

    double nullRate() {
        return total <= 0 ? 0.0 : nulls / total;
    }
}