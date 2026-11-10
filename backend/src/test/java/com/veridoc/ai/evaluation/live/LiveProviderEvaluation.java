package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The live-provider evaluation harness.
 *
 * <p><strong>The class name deliberately does not end in {@code IT}.</strong>
 * Failsafe's include list is {@code **&#47;*IT.java}, so the default build
 * cannot execute this class: neither {@code mvn test} (Surefire only owns
 * {@code *Test}) nor {@code mvn verify} (Failsafe only owns {@code *IT}) will
 * pick it up. The class is added to Failsafe's include list only by the
 * {@code live-eval} Maven profile, which is never active by default. That is
 * the structural half of the protection.
 *
 * <p>The behavioural half is the readiness preflight inherited from
 * {@link LiveEvaluationTestBase}: a run additionally needs
 * {@code -Dveridoc.eval.live=true}, both real credentials and a budget that
 * parses, and refuses to happen when the {@code CI} environment variable is
 * set. Both halves are independent, so neither a stale profile nor a stray
 * {@code -D} flag alone can start a paid run.
 *
 * <p>A live run therefore needs all three:
 *
 * <pre>
 * mvn -B -Plive-eval -Dveridoc.eval.live=true verify
 * </pre>
 *
 * <p>This step delivers the skeleton, the preflight and the budgeted model
 * decorators. The evaluation body remains disabled: nothing in this class
 * contacts Gemini or Groq, ingests a document or asks a case.
 */
class LiveProviderEvaluation extends LiveEvaluationTestBase {

    @Test
    @DisplayName("the live run is ready: activated, credentialed and inside a fresh budget")
    void liveRunIsReady() {
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady();

        assertThat(plan.activation().enabled())
                .as("the inherited @BeforeAll preflight must have rejected this run otherwise")
                .isTrue();
        assertThat(plan.activation().violations()).isEmpty();
        assertThat(plan.credentials().acceptable())
                .as("presence checked, validity still unproven: %s", plan.credentials().describe())
                .isTrue();
        assertThat(plan.budget().maxChatCalls()).isPositive();
        assertThat(plan.budget().maxEmbeddingCalls()).isPositive();
        assertThat(plan.budget().totalCalls())
                .as("no provider call may happen before the measured run")
                .isZero();
    }

    @Test
    @Disabled("Step 6 wires the measured live execution; this step delivers the guards only.")
    void liveEvaluationRun() {
        throw new UnsupportedOperationException(
                "live provider execution is implemented in a later step");
    }
}
