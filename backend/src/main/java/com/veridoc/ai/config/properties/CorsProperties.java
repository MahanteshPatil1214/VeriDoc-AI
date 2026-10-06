package com.veridoc.ai.config.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "veridoc.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:5173", "http://localhost:3000"}) List<String> allowedOrigins
) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}