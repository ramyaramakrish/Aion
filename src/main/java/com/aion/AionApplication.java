package com.aion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AionApplication {

    public static void main(String[] args) {
        SpringApplication.run(AionApplication.class, args);
    }
}
