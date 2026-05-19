package io.github.markpollack.experiment.result;

/**
 * Marker interface for domain-specific per-item execution details stored in
 * {@link ItemResult}.
 *
 * <p>
 * Shared experiment infrastructure ({@code ComparisonEngine}, {@code ResultStore},
 * {@code VerdictExtractor}) stores but does not interpret {@code ExecutionDetail}
 * contents. Domain-specific experiment types provide their own implementations:
 * <ul>
 * <li>{@code InvocationResult} — agent experiment execution details</li>
 * </ul>
 *
 * <p>
 * Consumers that need the concrete type should use {@code instanceof} checks.
 */
public interface ExecutionDetail {

}
