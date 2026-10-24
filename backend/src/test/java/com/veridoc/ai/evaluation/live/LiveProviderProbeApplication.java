package com.veridoc.ai.evaluation.live;

import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.infrastructure.config.AiConfig;
import com.veridoc.ai.infrastructure.config.GoogleGenAiHttpConfig;

/**
 * The smallest Spring context that contains the two real provider clients.
 *
 * <p><strong>Why this is not {@code VeriDocApplication}.</strong> A credential
 * probe answers one question - are these keys accepted - and nothing else. The
 * full application also needs PostgreSQL, pgvector, Redis, Flyway and the whole
 * service graph, so booting it here would turn "is the key wrong?" into "is
 * Docker running?", and would make a credential check cost a container start
 * per attempt. The slice imports the <em>production</em> AI wiring
 * ({@link AiConfig}, {@link GoogleGenAiHttpConfig}) and the upstream
 * auto-configurations for the two providers used in production, so the models
 * it yields are the same classes, options, timeouts and retry policies the
 * application uses. Everything else - ingestion, retrieval, chat, persistence -
 * is deliberately absent and is exercised by the measured run in a later step,
 * whose context is {@code VeriDocApplication}.
 *
 * <p><strong>No component scanning.</strong> There is no
 * {@code @SpringBootApplication} and no {@code @ComponentScan}, so nothing from
 * {@code com.veridoc.ai} is picked up: the context contains exactly the imports
 * above. Annotating this class {@link TestConfiguration} additionally keeps it
 * out of the component scan of any other context, because Spring Boot's test
 * support excludes {@code @TestConfiguration} classes from scanning. The class
 * lives in test sources and can never be packaged.
 *
 * <p><strong>No provider call happens at construction.</strong> Building the
 * Gemini {@code Client} and the Groq {@code OpenAiChatModel} only assembles HTTP
 * clients; neither SDK contacts the network until a model method is invoked,
 * and this class never invokes one.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProperties.class, EmbeddingProperties.class,
        GoogleGenAiEmbeddingConnectionProperties.class})
@ImportAutoConfiguration({ToolCallingAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class, GoogleGenAiTextEmbeddingAutoConfiguration.class})
@Import({AiConfig.class, GoogleGenAiHttpConfig.class})
public class LiveProviderProbeApplication {
}
