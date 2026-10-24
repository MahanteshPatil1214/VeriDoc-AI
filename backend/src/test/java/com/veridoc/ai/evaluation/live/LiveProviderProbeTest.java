package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

import com.veridoc.ai.evaluation.live.LiveProviderProbe.FailureCategory;
import com.veridoc.ai.evaluation.live.LiveProviderProbe.Report;
import com.veridoc.ai.evaluation.live.LiveProviderProbe.Result;

/**
 * Behaviour of the credential probe against fake models.
 *
 * <p>Every test here is offline: no credential, no container, no socket. The
 * fakes count their invocations, so the "exactly once, never retried" and
 * "failed calls are still charged" rules are asserted, not assumed.
 */
class LiveProviderProbeTest {

    private static final int DIMENSIONS = 8;

    @Test
    @DisplayName("a well-formed response from each provider passes, one invocation each")
    void wellFormedResponsesPass() {
        FakeChatModel chat = FakeChatModel.returning(chatResponse("ready"));
        FakeEmbeddingModel embedding = FakeEmbeddingModel.returning(vector(1f));

        Report report = probe(chat, embedding).run();

        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).extracting(Result::provider)
                .containsExactly(LiveProviderProbe.CHAT_PROVIDER,
                        LiveProviderProbe.EMBEDDING_PROVIDER);
        assertThat(report.results()).allSatisfy(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.failureCategory()).isNull();
            assertThat(result.elapsedMillis()).isGreaterThanOrEqualTo(0L);
        });
        assertThat(chat.calls).isEqualTo(1);
        assertThat(embedding.calls).isEqualTo(1);
        assertThat(report.summary())
                .contains("verdict: PASS")
                .contains(LiveProviderProbe.CHAT_PROVIDER)
                .contains(LiveProviderProbe.EMBEDDING_PROVIDER);
    }

    @Test
    @DisplayName("a chat response with no generation is reported as missing")
    void chatWithoutGenerationIsMissing() {
        Result result = probe(FakeChatModel.returning(chatResponse(null)), embeddingOk()).run()
                .results().get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureCategory()).isEqualTo(FailureCategory.MISSING_RESPONSE);
    }

    @Test
    @DisplayName("a null chat response is reported as missing")
    void nullChatResponseIsMissing() {
        Result result = probe(FakeChatModel.returning(null), embeddingOk()).run()
                .results().get(0);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.MISSING_RESPONSE);
    }

    @Test
    @DisplayName("a blank chat answer is reported as empty")
    void blankChatAnswerIsEmpty() {
        Result result = probe(FakeChatModel.returning(chatResponse("   ")), embeddingOk()).run()
                .results().get(0);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("a vector of the wrong width is reported as invalid dimensions")
    void wrongVectorWidthIsInvalid() {
        Result result = probe(chatOk(), FakeEmbeddingModel.returning(vector(1f, 2f))).run()
                .results().get(1);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.INVALID_DIMENSIONS);
    }

    @Test
    @DisplayName("an empty vector is reported as an empty response, not as valid output")
    void emptyVectorIsEmpty() {
        Result result = probe(chatOk(), FakeEmbeddingModel.returning(new float[0])).run()
                .results().get(1);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("a vector containing a non-finite value is rejected")
    void nonFiniteVectorIsRejected() {
        float[] vector = vector(1f);
        vector[3] = Float.NaN;

        Result result = probe(chatOk(), FakeEmbeddingModel.returning(vector)).run().results().get(1);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.NON_FINITE_VECTOR);
    }

    @Test
    @DisplayName("an all-zero vector is structurally present but unusable")
    void zeroVectorIsRejected() {
        Result result = probe(chatOk(), FakeEmbeddingModel.returning(new float[DIMENSIONS])).run()
                .results().get(1);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.ZERO_VECTOR);
    }

    @Test
    @DisplayName("HTTP 401 and 403 are reported as an authentication failure")
    void authenticationFailuresAreClassified() {
        assertThat(probe(failingChat(HttpStatus.UNAUTHORIZED), embeddingOk()).run()
                .results().get(0).failureCategory())
                .isEqualTo(FailureCategory.AUTHENTICATION);
        assertThat(probe(failingChat(HttpStatus.FORBIDDEN), embeddingOk()).run()
                .results().get(0).failureCategory())
                .isEqualTo(FailureCategory.AUTHENTICATION);
    }

    @Test
    @DisplayName("rate limits, provider failures and other rejections stay distinguishable")
    void otherProviderFailuresAreClassified() {
        assertThat(probe(failingChat(HttpStatus.TOO_MANY_REQUESTS), embeddingOk()).run()
                .results().get(0).failureCategory())
                .isEqualTo(FailureCategory.RATE_LIMITED);
        assertThat(probe(failingChat(HttpStatus.INTERNAL_SERVER_ERROR), embeddingOk()).run()
                .results().get(0).failureCategory())
                .isEqualTo(FailureCategory.PROVIDER_UNAVAILABLE);
        assertThat(probe(failingChat(HttpStatus.BAD_REQUEST), embeddingOk()).run()
                .results().get(0).failureCategory())
                .isEqualTo(FailureCategory.REQUEST_REJECTED);
    }

    @Test
    @DisplayName("a wrapped status failure is still classified from the cause chain")
    void wrappedFailuresAreClassified() {
        FakeChatModel chat = FakeChatModel.failing(
                new IllegalStateException("wrapper", httpFailure(HttpStatus.SERVICE_UNAVAILABLE)));

        assertThat(probe(chat, embeddingOk()).run().results().get(0).failureCategory())
                .isEqualTo(FailureCategory.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("timeouts and connection failures have their own categories")
    void transportFailuresAreClassified() {
        assertThat(probe(FakeChatModel.failing(new RuntimeException(new TimeoutException())),
                embeddingOk()).run().results().get(0).failureCategory())
                .isEqualTo(FailureCategory.TIMEOUT);
        assertThat(probe(FakeChatModel.failing(new RuntimeException(new java.net.ConnectException())),
                embeddingOk()).run().results().get(0).failureCategory())
                .isEqualTo(FailureCategory.NETWORK);
    }

    @Test
    @DisplayName("an unclassifiable failure is reported as unexpected, never as a message")
    void unclassifiedFailuresStayOpaque() {
        Result result = probe(FakeChatModel.failing(new IllegalStateException("boom")), embeddingOk())
                .run().results().get(0);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.UNEXPECTED_ERROR);
        assertThat(result.describe()).doesNotContain("boom");
    }

    @Test
    @DisplayName("a provider failure on one model does not hide the other result")
    void oneFailureDoesNotHideTheOther() {
        Report report = probe(failingChat(HttpStatus.UNAUTHORIZED), embeddingOk()).run();

        assertThat(report.results()).hasSize(2);
        assertThat(report.allPassed()).isFalse();
        assertThat(report.results().get(0).passed()).isFalse();
        assertThat(report.results().get(1).passed()).isTrue();
        assertThat(report.summary()).contains("verdict: FAIL");
    }

    @Test
    @DisplayName("a rejected call consumes no slot and never reaches the provider")
    void rejectedCallNeverReachesTheProvider() {
        LiveEvalBudget budget = new LiveEvalBudget(1, 1, 1, Duration.ofMinutes(1));
        budget.recordChatCall();
        FakeChatModel chat = FakeChatModel.returning(chatResponse("ready"));

        Result result = probe(new BudgetedChatModel(chat, budget), embeddingOk()).run()
                .results().get(0);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.BUDGET_EXHAUSTED);
        assertThat(chat.calls).as("a rejected call must not reach the delegate").isZero();
        assertThat(budget.chatCalls())
                .as("the rejected attempt is still counted, so a breach stays visible")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a failed invocation is charged once and is not retried by the probe")
    void failedInvocationIsChargedOnceAndNotRetried() {
        LiveEvalBudget budget = new LiveEvalBudget(1, 5, 5, Duration.ofMinutes(1));
        FakeChatModel chat = failingChat(HttpStatus.UNAUTHORIZED);

        Result result = probe(new BudgetedChatModel(chat, budget), embeddingOk()).run()
                .results().get(0);

        assertThat(result.failureCategory()).isEqualTo(FailureCategory.AUTHENTICATION);
        assertThat(chat.calls).as("the probe must not retry a failed invocation").isEqualTo(1);
        assertThat(budget.chatCalls()).as("the failed call was still charged").isEqualTo(1);
    }

    @Test
    @DisplayName("the report never contains the probe inputs or the provider's content")
    void reportNeverLeaksContent() {
        FakeChatModel chat = FakeChatModel.returning(chatResponse("provider-answer-text"));
        FakeEmbeddingModel embedding = FakeEmbeddingModel.returning(vector(0.25f));

        String summary = probe(chat, embedding).run().summary();

        assertThat(summary)
                .doesNotContain(LiveProviderProbe.CHAT_PROBE_PROMPT)
                .doesNotContain(LiveProviderProbe.EMBEDDING_PROBE_INPUT)
                .doesNotContain("provider-answer-text")
                .doesNotContain("0.25")
                .contains("ms");
    }

    @Test
    @DisplayName("the pass/fail invariant of a result is enforced by construction")
    void resultInvariantsAreEnforced() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new Result("x", true, 1L, FailureCategory.TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new Result("x", false, 1L, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero expected width is a programming error, not a probe")
    void expectedDimensionsMustBePositive() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new LiveProviderProbe(chatOk(), embeddingOk(), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the probe entry point stays out of the default build and out of the harness base")
    void probeIsStructurallySeparateFromTheHarness() throws NoSuchMethodException {
        assertThat(LiveProviderCredentialProbe.class.getSimpleName())
                .as("Surefire owns *Test and Failsafe owns *IT")
                .doesNotEndWith("Test")
                .doesNotEndWith("IT");
        assertThat(LiveProviderCredentialProbe.class.getSuperclass())
                .as("the probe must not inherit the harness base or its Spring test context")
                .isEqualTo(Object.class);
        assertThat(LiveProviderEvaluation.class
                .getDeclaredMethod("liveEvaluationRun"))
                .as("the measured evaluation body is wired")
                .isNotNull();
    }

    @Test
    @DisplayName("the probe slice is a test configuration, not a scanned application")
    void probeSliceIsATestConfiguration() {
        assertThat(LiveProviderProbeApplication.class
                .getAnnotation(org.springframework.boot.test.context.TestConfiguration.class))
                .as("Spring Boot's test support excludes @TestConfiguration from component scanning")
                .isNotNull();
        assertThat(LiveProviderProbeApplication.class
                .getAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class))
                .as("component scanning would drag in the whole application")
                .isNull();
    }

    private static LiveProviderProbe probe(ChatModel chat, EmbeddingModel embedding) {
        return new LiveProviderProbe(chat, embedding, DIMENSIONS);
    }

    private static FakeChatModel chatOk() {
        return FakeChatModel.returning(chatResponse("ready"));
    }

    private static FakeEmbeddingModel embeddingOk() {
        return FakeEmbeddingModel.returning(vector(1f));
    }

    private static FakeChatModel failingChat(HttpStatus status) {
        return FakeChatModel.failing(httpFailure(status));
    }

    private static RuntimeException httpFailure(HttpStatus status) {
        return new HttpStatusCodeException(status) {
        };
    }

    private static ChatResponse chatResponse(String text) {
        if (text == null) {
            return new ChatResponse(List.of());
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static float[] vector(float value) {
        float[] vector = new float[DIMENSIONS];
        java.util.Arrays.fill(vector, value);
        return vector;
    }

    private static float[] vector(float first, float second) {
        return new float[] { first, second };
    }

    /** A chat model that answers, fails or returns null, counting invocations. */
    private static final class FakeChatModel implements ChatModel {

        private final ChatResponse response;
        private final RuntimeException failure;
        private int calls;

        private FakeChatModel(ChatResponse response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        static FakeChatModel returning(ChatResponse response) {
            return new FakeChatModel(response, null);
        }

        static FakeChatModel failing(RuntimeException failure) {
            return new FakeChatModel(null, failure);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    /** An embedding model that answers through {@code call}, like the real one. */
    private static final class FakeEmbeddingModel implements EmbeddingModel {

        private final float[] vector;
        private final RuntimeException failure;
        private int calls;

        private FakeEmbeddingModel(float[] vector, RuntimeException failure) {
            this.vector = vector;
            this.failure = failure;
        }

        static FakeEmbeddingModel returning(float[] vector) {
            return new FakeEmbeddingModel(vector, null);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("the probe never embeds a document");
        }

        @Override
        public int dimensions() {
            return vector == null ? 0 : vector.length;
        }
    }
}
