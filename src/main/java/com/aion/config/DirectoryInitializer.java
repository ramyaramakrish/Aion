package com.aion.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;

@Component
public class DirectoryInitializer {

    private static final Logger log = LoggerFactory.getLogger(DirectoryInitializer.class);
    private final AionProperties properties;

    public DirectoryInitializer(AionProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(properties.documentsDir());
        Files.createDirectories(properties.outputDir());
        log.info("Ensured required directories exist: {} and {}", properties.documentsDir(), properties.outputDir());
    }
}