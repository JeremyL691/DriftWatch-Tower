package com.driftwatch.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "driftwatch.detector.field-range")
public class FieldRangeProperties {

    private Map<String, FieldRangeDetector.Bounds> fields = new LinkedHashMap<>();

    public Map<String, FieldRangeDetector.Bounds> getFields() {
        return fields;
    }

    public void setFields(Map<String, FieldRangeDetector.Bounds> fields) {
        this.fields = fields;
    }
}
