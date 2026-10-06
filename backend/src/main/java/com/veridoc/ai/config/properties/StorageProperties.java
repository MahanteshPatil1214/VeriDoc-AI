package com.veridoc.ai.config.properties;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local filesystem storage for uploaded originals. User-supplied filenames are
 * never used to build a path; see
 * {@code com.veridoc.ai.document.storage.StorageKeyFactory}.
 */
@ConfigurationProperties(prefix = "veridoc.storage")
public record StorageProperties(

        @DefaultValue("./data/storage") Path root,

        @DefaultValue("20971520") long maxFileSize,

        @DefaultValue("500") int maxPages
) {
    public StorageProperties {
        if (maxFileSize <= 0) {
            throw new IllegalStateException("veridoc.storage.max-file-size must be positive");
        }
        if (maxPages <= 0) {
            throw new IllegalStateException("veridoc.storage.max-pages must be positive");
        }
    }
}