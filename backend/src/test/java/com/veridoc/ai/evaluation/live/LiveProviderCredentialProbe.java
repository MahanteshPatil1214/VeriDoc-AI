package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ConfigurableApplicationContext;

import com.veridoc.ai.infrastructure.config.AiConfig;

/**
 * The explicit credential probe: one authenticated embedding call to Gemini and
 * one authenticated chat call to Groq, and nothing else.
 *
 * <p><strong>Activation.</strong> The class name deliberately ends in neither
 * {@code IT} nor {@code Test}, so the default build cannot run it: Surefire
 * only owns {@code *Test}, Failsafe only owns {@code *IT}, and the
 * {@code live-eval} Maven profile is the only thing that appends this class to
 * Failsafe's include list. Running it additionally requires the same
 * {@link LiveEvalPreflight} as the measured run - the profile stamp, an explicit
 * {@code -Dveridoc.eval.live=true}, both real credentials and no {@code CI}
 * variable - because the preflight is the only way to obtain the plan the
 * context is built from.
 *
 * <pre>
 * mvn -B -Plive-eval -Dveridoc.eval.live=true \
 *     -Dit.test=com.veridoc.ai.evaluation.live.LiveProviderCredentialProbe \
 *     test-compile failsafe:integration-test failsafe:verify
 * </pre>
 *
 * <p><strong>Separation from the evaluation.</strong> This class shares no
 * state with {@link LiveProviderEvaluation}: it does not extend
 * {@link LiveEvaluationTestBase}, does not read the dataset and does not boot
 * the application. The harness's {@code liveEvaluationRun} body stays disabled;
 * the probe is the only live entry point that is executable today.
 *
 * <p><strong>Cost.</strong> Two model invocations, each charged to the run
 * budget by {@link BudgetedChatModel} / {@link BudgetedEmbeddingModel} before
 * the provider is contacted. Failed invocations are charged too and are never
 * retried here. Either way the budget's wall-clock deadline still applies.
 *
 * <p><strong>Output.</strong> Provider name, pass/fail, elapsed time and - on
 * failure - one safe category. See {@link LiveProviderProbe} for what is
 * deliberately never printed.
 */
class LiveProviderCredentialProbe {

    @Test
    @DisplayName("Gemini embeddings and Groq chat each accept one synthetic probe")
    void bothConfiguredProvidersAcceptOneProbe() {
        // Pure checks first: activation, credentials, budget configuration. No
        // Spring context, no client and no socket can exist before this returns.
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady();

        LiveProviderProbe.Report report;
        try (ConfigurableApplicationContext context = LiveEvalGuardedContext.create(
                plan, LiveProviderProbeApplication.class)) {

            LiveProviderProbe probe = new LiveProviderProbe(
                    context.getBean(ChatModel.class),
                    context.getBean(EmbeddingModel.class),
                    context.getBean(AiConfig.EmbeddingDimensions.class).dimensions());
            report = probe.run();
        }

        System.out.println(report.summary());

        // Counted before the delegate runs, so these hold for failures as well:
        // exactly one invocation each, and nothing spent while the context started.
        assertThat(plan.budget().chatCalls())
                .as("the probe must make exactly one chat invocation (%s)", report.summary())
                .isEqualTo(1);
        assertThat(plan.budget().embeddingCalls())
                .as("the probe must make exactly one embedding invocation (%s)", report.summary())
                .isEqualTo(1);

        assertThat(report.allPassed())
                .as("provider probe result:%n%s", report.summary())
                .isTrue();
    }
}
