package com.aion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "aion")
public record AionProperties(
        Path outputDir
) {
    public AionProperties {
        if (outputDir == null) {
            outputDir = Path.of("Output");
        }
    }
}
