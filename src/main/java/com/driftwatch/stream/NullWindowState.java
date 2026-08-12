package com.driftwatch.stream;

/** Running null-spike totals for one (source,eventType,fieldPath) within one window. */
record NullWindowState(long windowStart, double total, double nulls) {}
