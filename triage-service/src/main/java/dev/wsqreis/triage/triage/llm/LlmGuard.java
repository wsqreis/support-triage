package dev.wsqreis.triage.triage.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Every LLM call goes through here: a time limit per call and one circuit breaker for the model.
 * When the model keeps failing the circuit opens, and calls fail immediately for a minute instead of
 * each waiting for a timeout. Callers decide what to do on failure.
 */
@Component
public class LlmGuard {

    private final CircuitBreaker breaker;
    private final TimeLimiter timeLimiter;
    // Each call blocks on network I/O: virtual threads keep that cheap.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmGuard(@Value("${triage.llm.timeout:20s}") Duration timeout,
                    @Value("${triage.llm.circuit-open-for:60s}") Duration openFor) {
        this.breaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .build());
        this.timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
    }

    public <T> T call(Supplier<T> llmCall) throws Exception {
        return breaker.executeCallable(() -> timeLimiter.executeFutureSupplier(
                () -> CompletableFuture.supplyAsync(llmCall, executor)));
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** The root cause, for logs. */
    public static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
