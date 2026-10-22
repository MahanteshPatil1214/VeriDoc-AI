package com.veridoc.ai.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.veridoc.ai.config.properties.RagProperties;

/**
 * Evaluation-only wiring: makes the {@link RecordingPromptBuilder} primary so
 * the retrieval evidence handed to the model can be observed.
 *
 * <p>Imported only by {@code RagEvaluationIT}; every other integration test
 * keeps the plain {@code GroundedPromptBuilder} bean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EvalTestConfig {

    @Bean
    @Primary
    RecordingPromptBuilder recordingPromptBuilder(RagProperties properties) {
        return new RecordingPromptBuilder(properties);
    }
}
