package com.driftwatch.quality.schema;

import java.util.Map;

public interface SchemaBaselineProvider {
    Map<String, String> activeLeafFieldTypes(String eventType);
}
