package com.driftwatch;

import com.driftwatch.quality.FieldRangeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FieldRangeProperties.class)
public class DriftWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriftWatchApplication.class, args);
    }
}
