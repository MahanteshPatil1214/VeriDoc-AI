package com.veridoc.ai.evaluation.live;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.veridoc.ai.support.EvalTestConfig;

/**
 * Base class for the live-provider evaluation harness.
 *
 * <p>This is deliberately <strong>not</strong> a subclass of
 * {@code AbstractPostgresIntegrationTest}. That base class belongs to the
 * deterministic suite and does two things a live run must not inherit:
 *
 * <ul>
 *   <li>it activates the {@code test} Spring profile, whose
 *       {@code application-test.yml} pins both API keys to the placeholder
 *       {@code "test-key-not-used"} and overrides the retrieval floor
 *       ({@code min-score} 0.15 &#8594; 0.2), the query-rewriting switch
 *       (true &#8594; false) and RAG debug logging (false &#8594; true);</li>
 *   <li>its {@code @DynamicPropertySource} re-registers those placeholder keys
 *       at the highest property precedence, so a live run inheriting it would
 *       authenticate against a fake key no matter what the environment
 *       contains.</li>
 * </ul>
 *
 * <p>Instead this base uses the {@code eval-live} Spring profile, whose
 * {@code application-eval-live.yml} keeps the production retrieval floor
 * (0.15), keeps query rewriting on, keeps RAG debug logging off, and inherits
 * none of the {@code application-test.yml} overrides. The provider
 * credentials are <strong>not</strong> registered here either: they come from
 * {@code GROQ_API_KEY} and {@code GEMINI_API_KEY} through the regular
 * production property placeholders.
 *
 * <p>{@link EvalTestConfig} is imported for one reason only: it makes the
 * recording prompt builder the primary one, so the evidence handed to the
 * model can be observed exactly as the deterministic harness observes it. It
 * does not provide any model. In particular {@code IngestionTestConfig} is
 * <strong>not</strong> imported: its deterministic chat and embedding stubs
 * would replace the real Gemini and Groq clients and the "live" run would
 * quietly measure the double again.
 *
 * <p>The preflight runs in {@code @BeforeAll}. JUnit executes {@code @BeforeAll}
 * callbacks - including inherited ones - before any test instance is created,
 * and the Spring test context is only created when the test instance is
 * prepared. The preflight therefore fails before the application context is
 * refreshed, before a provider client exists and before a container is started.
 * It checks the activation contract, both credentials and the budget in one
 * pass, and reports every violation together.
 *
 * <p><strong>Step 4 scope:</strong> this base deliberately carries no
 * {@code @SpringBootTest} annotation yet. The ordering safeguards now exist -
 * {@link LiveEvalPreflight} is pure and {@link LiveEvalGuardedContext} refuses
 * to start a context without a plan - but booting <em>this</em> application
 * needs real credentials, which a verification run of this step must not
 * require. A later step replaces the annotation with
 * {@link LiveEvalGuardedContext} (or restores {@code @SpringBootTest} once the
 * preflight has been shown to run first). Everything else - the Spring profile,
 * the imports, the readiness gate and the throwaway infrastructure - is in
 * place already, and the harness inherits it.
 */
@ActiveProfiles("eval-live")
@Import(EvalTestConfig.class)
public abstract class LiveEvaluationTestBase {

    /**
     * The readiness gate: activation, credentials and budget configuration.
     *
     * <p>Declared here so that every live class inherits it. It throws
     * {@link LiveEvalPreflightException} - a {@link LiveEvalActivationException}
     * - when the {@code live-eval} Maven profile is not active, when the
     * {@code veridoc.eval.live=true} opt-in property is missing, when the
     * {@code CI} environment variable is set, when either provider credential is
     * missing, blank, an obvious placeholder or implausibly short, or when the
     * budget is configured with an unusable value.
     *
     * <p>It runs in {@code @BeforeAll}, so it fails before the Spring context is
     * created, before a provider client is constructed and before a container is
     * started. The message never contains a credential value.
     */
    @BeforeAll
    static void requireLiveEvaluationReadiness() {
        LiveEvalPreflight.requireReady();
    }

    /**
     * Points the live context at throwaway infrastructure.
     *
     * <p>Only the datasource, the cache and the migration location are
     * registered. No API key is registered: the live run must use the real
     * credentials from the environment.
     */
    @DynamicPropertySource
    static void registerLiveInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> LiveInfrastructure.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> LiveInfrastructure.postgres().getUsername());
        registry.add("spring.datasource.password", () -> LiveInfrastructure.postgres().getPassword());
        registry.add("spring.data.redis.url", () -> "redis://" + LiveInfrastructure.redis().getHost()
                + ":" + LiveInfrastructure.redis().getMappedPort(6379));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    /**
     * The containers the live run needs, started lazily.
     *
     * <p>They are created on first use rather than in a static initialiser, so
     * a run that is rejected by the preflight never starts a container: the
     * preflight fails first, and nothing else has been touched.
     */
    private static final class LiveInfrastructure {

        /**
         * The application requires the pgvector extension, so the plain
         * PostgreSQL image cannot be used.
         */
        private static final DockerImageName POSTGRES_IMAGE =
                DockerImageName.parse("pgvector/pgvector:pg17");

        private static PostgreSQLContainer<?> postgres;
        private static GenericContainer<?> redis;

        private LiveInfrastructure() {
        }

        static synchronized PostgreSQLContainer<?> postgres() {
            if (postgres == null) {
                // Testcontainers' built-in throwaway credentials are used on
                // purpose: the container lives for one run and the values are
                // read back through getUsername()/getPassword() in
                // registerLiveInfrastructure, so there is nothing worth pinning here.
                postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
                postgres.start();
            }
            return postgres;
        }

        /**
         * Redis backs rate limiting and the actuator health indicator, so the
         * live context needs a real instance just like production.
         */
        static synchronized GenericContainer<?> redis() {
            if (redis == null) {
                redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379);
                redis.start();
            }
            return redis;
        }
    }
}
