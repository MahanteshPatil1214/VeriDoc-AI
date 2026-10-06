package com.veridoc.ai.infrastructure.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Cross-cutting infrastructure beans.
 */
@Configuration
@EnableScheduling
public class InfrastructureConfig {

    /**
     * Redis for rate limiting and short-lived caches. Rate limiting degrades
     * gracefully when Redis is unavailable, so the template is always present.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /** Stages surfaced to the frontend so it can render a stable stepper. */
    @Bean
    public List<String> processingStageOrder() {
        return List.of("UPLOADED", "EXTRACTING", "CHUNKING", "EMBEDDING", "INDEXING", "READY");
    }
}