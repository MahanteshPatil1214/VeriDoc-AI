package com.veridoc.ai;

import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.ChunkingProperties;
import com.veridoc.ai.config.properties.CorsProperties;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.config.properties.JwtProperties;
import com.veridoc.ai.config.properties.RagProperties;
import com.veridoc.ai.config.properties.RateLimitProperties;
import com.veridoc.ai.config.properties.RetrievalProperties;
import com.veridoc.ai.config.properties.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
        JwtProperties.class,
        StorageProperties.class,
        ChunkingProperties.class,
        EmbeddingProperties.class,
        RetrievalProperties.class,
        RagProperties.class,
        AiProperties.class,
        RateLimitProperties.class,
        CorsProperties.class
})
public class VeriDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeriDocApplication.class, args);
    }
}