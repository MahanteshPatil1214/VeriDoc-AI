package com.veridoc.ai.evaluation.live;

import java.util.Objects;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Creates the Spring context for a live run - but only from a plan that has
 * already passed {@link LiveEvalPreflight}.
 *
 * <p><strong>Why the context is created here and not by
 * {@code @SpringBootTest}.</strong> Spring's test support creates the
 * application context while the test instance is being prepared, which happens
 * before any test method body runs and, as Step 3 established empirically, even
 * for a {@code @Disabled} method. Any guard living in a test body - or even in
 * {@code @BeforeAll} of a class whose context is created by
 * {@code @SpringBootTest} - is therefore too late to stop the provider clients
 * from being constructed. A context built programmatically after the preflight
 * flips the order around:
 *
 * <pre>
 *   preflight (pure, no Spring)        &#8594; throws, nothing exists yet
 *        &#8594; SpringApplication.run  &#8594; bean instantiation (provider clients)
 *        &#8594; budgeted decorators installed while beans are post-processed
 * </pre>
 *
 * <p>The only way to obtain a {@link LiveEvalPreflight.Plan} is
 * {@link LiveEvalPreflight#requireReady()}, so a caller cannot reach
 * {@code run()} with an unvalidated configuration. Deferring the preflight
 * until after the context exists is not expressible through this API.
 *
 * <p>Budget enforcement is installed as a bean post-processor through a context
 * initialiser, i.e. before the context is refreshed and before the first
 * singleton is created. Every model bean - and therefore every consumer
 * injected with one - is decorated. The decorator is never installed in a
 * context built any other way, so the deterministic integration suite keeps its
 * stub models.
 */
public final class LiveEvalGuardedContext {

    private LiveEvalGuardedContext() {
    }

    /**
     * Starts the application context for a validated live run.
     *
     * <p>The context is created without a web server: the evaluation drives
     * services directly and must not bind a port.
     *
     * @param plan a plan returned by {@link LiveEvalPreflight#requireReady()}
     * @param applicationClass the application to start
     * @param args Spring Boot program arguments
     * @return a running context, closed by the caller
     * @throws LiveEvalBudgetExceededException when the plan's wall-clock budget
     *         is already spent
     */
    public static ConfigurableApplicationContext create(
            LiveEvalPreflight.Plan plan, Class<?> applicationClass, String... args) {

        Objects.requireNonNull(plan, "a live context requires a validated preflight plan");
        Objects.requireNonNull(applicationClass, "an application class must be provided");

        // Last cheap check before anything is built: a run whose time box is
        // already gone must not even start its context.
        plan.budget().assertWithinDeadline();

        LiveEvalBudget budget = plan.budget();

        // Applied in prepareContext(), i.e. before refresh() creates the first
        // singleton and long before any provider client could be constructed.
        ApplicationContextInitializer<ConfigurableApplicationContext> budgetedModels =
                context -> context.getBeanFactory()
                        .addBeanPostProcessor(new BudgetedModelBeanPostProcessor(budget));

        return new SpringApplicationBuilder(applicationClass)
                .web(WebApplicationType.NONE)
                .profiles("eval-live")
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .initializers(budgetedModels)
                .run(args);
    }
}
