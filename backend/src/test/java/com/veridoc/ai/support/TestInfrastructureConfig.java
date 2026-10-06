package com.veridoc.ai.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Placeholder for test-only bean overrides. Kept as a real type so
 * {@code @Import} targets stay stable as more tests need substitutions.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructureConfig {

    @Bean
    String testProfileMarker() {
        return "veridoc-test";
    }
}