package com.driftwatch.stream;

/** Event count for one (scope, window); {@code fired} keeps it to one alert per window. */
record AnomalyWindowState(long windowStart, long count, boolean fired) {

    AnomalyWindowState incremented() {
        return new AnomalyWindowState(windowStart, count + 1, fired);
    }

    AnomalyWindowState firedOnce() {
        return new AnomalyWindowState(windowStart, count, true);
    }
}