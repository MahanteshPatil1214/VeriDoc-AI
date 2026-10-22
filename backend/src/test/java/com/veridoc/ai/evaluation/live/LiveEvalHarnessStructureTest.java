package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.EvalTestConfig;
import com.veridoc.ai.support.IngestionTestConfig;

/**
 * Structural guarantees of the live harness.
 *
 * <p>These tests pin the shape of the harness rather than its behaviour: that
 * the class cannot be picked up by the default build, that it does not inherit
 * the deterministic suite's wiring, and that the live Spring profile really
 * carries the production retrieval settings instead of the test profile's
 * overrides.
 */
class LiveEvalHarnessStructureTest {

    @Test
    @DisplayName("the harness class name does not match the Failsafe include pattern")
    void harnessNameIsNotAnIntegrationTest() {
        assertThat(LiveProviderEvaluation.class.getSimpleName())
                .as("Failsafe only includes **/*IT.java, so this name keeps the harness "
                        + "out of the default build")
                .doesNotEndWith("IT");
        assertThat(LiveProviderEvaluation.class.getSimpleName())
                .as("Surefire only includes *Test, so this name keeps the harness "
                        + "out of `mvn test`")
                .doesNotEndWith("Test");
    }

    @Test
    @DisplayName("the live harness does not inherit the deterministic integration base")
    void harnessDoesNotExtendTheDeterministicBase() {
        assertThat(LiveEvaluationTestBase.class.getSuperclass())
                .as("the deterministic base pins the API keys to a placeholder and "
                        + "activates the test profile")
                .isEqualTo(Object.class);
        assertThat(AbstractPostgresIntegrationTest.class.isAssignableFrom(LiveEvaluationTestBase.class))
                .as("the deterministic base must stay the base of the deterministic suite only")
                .isFalse();
    }

    @Test
    @DisplayName("the live base uses the eval-live Spring profile, not the test profile")
    void liveBaseUsesItsOwnSpringProfile() {
        ActiveProfiles profiles = LiveEvaluationTestBase.class.getAnnotation(ActiveProfiles.class);

        assertThat(profiles).isNotNull();
        assertThat(List.of(profiles.value())).containsExactly("eval-live");
        assertThat(List.of(profiles.value())).doesNotContain("test");
    }

    @Test
    @DisplayName("the live base imports the recording prompt builder but not the deterministic models")
    void liveBaseDoesNotImportTheDeterministicModels() {
        Import imports = LiveEvaluationTestBase.class.getAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(List.of(imports.value()))
                .as("the recording prompt builder is needed to capture the evidence")
                .contains(EvalTestConfig.class);
        assertThat(List.of(imports.value()))
                .as("the deterministic chat and embedding stubs would replace the real "
                        + "Gemini and Groq clients")
                .doesNotContain(IngestionTestConfig.class);
    }

    @Test
    @DisplayName("the harness inherits the activation guard from the live base")
    void harnessInheritsTheActivationGuard() {
        assertThat(LiveProviderEvaluation.class.getSuperclass())
                .as("the harness must inherit the base, and with it the guard")
                .isEqualTo(LiveEvaluationTestBase.class);

        boolean guardFound = Arrays.stream(LiveEvaluationTestBase.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(BeforeAll.class));
        assertThat(guardFound)
                .as("the base must run the guard in @BeforeAll, before any test instance exists")
                .isTrue();
    }

    @Test
    @DisplayName("the live Spring profile keeps the production retrieval settings")
    void liveProfileKeepsProductionRetrievalSettings() {
        Map<String, Object> live = veridocSection("application-eval-live.yml");

        assertThat(live.get("retrieval")).isEqualTo(Map.of("min-score", 0.15));
        assertThat(live.get("rag")).isEqualTo(
                Map.of("enable-query-rewriting", true, "debug-enabled", false));
    }

    @Test
    @DisplayName("the live Spring profile does not inherit the test profile's overrides")
    void liveProfileDiffersFromTheTestProfile() {
        Map<String, Object> live = veridocSection("application-eval-live.yml");
        Map<String, Object> test = veridocSection("application-test.yml");

        Map<String, Object> liveRetrieval = (Map<String, Object>) live.get("retrieval");
        Map<String, Object> testRetrieval = (Map<String, Object>) test.get("retrieval");
        assertThat(liveRetrieval.get("min-score"))
                .as("the test profile raises the similarity floor for the deterministic embedder")
                .isNotEqualTo(testRetrieval.get("min-score"));

        Map<String, Object> liveRag = (Map<String, Object>) live.get("rag");
        Map<String, Object> testRag = (Map<String, Object>) test.get("rag");
        assertThat(liveRag.get("enable-query-rewriting"))
                .isNotEqualTo(testRag.get("enable-query-rewriting"));
        assertThat(liveRag.get("debug-enabled"))
                .isNotEqualTo(testRag.get("debug-enabled"));
    }

    @Test
    @DisplayName("the live Spring profile never pins the provider credentials")
    void liveProfileDoesNotPinCredentials() {
        Map<String, Object> live = veridocSection("application-eval-live.yml");

        assertThat(live.toString())
                .as("the live run must authenticate with the real keys from the environment")
                .doesNotContain("api-key")
                .doesNotContain("test-key-not-used");
    }

    @Test
    @DisplayName("the live base still boots no context in this step")
    void liveBaseDoesNotBootAContextYet() {
        assertThat(LiveEvaluationTestBase.class.getAnnotation(SpringBootTest.class))
                .as("the provider must stay out of the verification path until the measured run")
                .isNull();
    }

    @Test
    @DisplayName("the guards and decorators are test classes with no Spring stereotype")
    void guardsAreTestOnlyAndNotComponentScanned() {
        List<Class<?>> guards = List.of(
                LiveEvalActivation.class,
                LiveEvalGuard.class,
                LiveEvalCredentials.class,
                LiveEvalBudget.class,
                LiveEvalPreflight.class,
                LiveEvalGuardedContext.class,
                BudgetedChatModel.class,
                BudgetedEmbeddingModel.class,
                BudgetedModelBeanPostProcessor.class);

        for (Class<?> type : guards) {
            for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
                String annotationName = annotation.annotationType().getName();
                assertThat(annotationName)
                        .as("%s must not be picked up by component scanning",
                                type.getSimpleName())
                        .doesNotStartWith("org.springframework.stereotype")
                        .isNotEqualTo(Configuration.class.getName());
            }
            assertThat(type.getResource(type.getSimpleName() + ".class").toString())
                    .as("%s must be compiled into test classes, never into the application",
                            type.getSimpleName())
                    .contains("/test-classes/");
        }
    }

    @Test
    @DisplayName("the deterministic configuration never declares a live or budgeted model")
    void deterministicConfigurationDeclaresNoLiveModels() {
        for (Class<?> configuration : List.of(IngestionTestConfig.class, EvalTestConfig.class)) {
            for (java.lang.reflect.Method method : configuration.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("%s.%s must not return a live model",
                                configuration.getSimpleName(), method.getName())
                        .doesNotContain("Budgeted")
                        .doesNotContain("LiveEval");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .as("%s.%s must not depend on a live guard",
                                    configuration.getSimpleName(), method.getName())
                            .doesNotContain("Budgeted")
                            .doesNotContain("LiveEval");
                }
            }
        }
    }

    /** The {@code veridoc} section of a YAML file on the test classpath. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> veridocSection(String resource) {
        try (InputStream stream = LiveEvalHarnessStructureTest.class
                .getResourceAsStream("/" + resource)) {
            assertThat(stream).as("missing classpath resource %s", resource).isNotNull();
            Map<String, Object> document = new Yaml().load(stream);
            Object veridoc = document.get("veridoc");
            assertThat(veridoc).as("%s has no veridoc section", resource).isInstanceOf(Map.class);
            return (Map<String, Object>) veridoc;
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }
}
