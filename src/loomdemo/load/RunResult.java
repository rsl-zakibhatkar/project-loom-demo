package loomdemo.load;

import loomdemo.Mode;
import loomdemo.server.OrderServer;

import java.util.Arrays;
import java.util.Locale;

/**
 * The outcome of one load test. One of these per mode is held by the UI at a time —
 * running a mode replaces that side's result and leaves the other side untouched.
 */
public record RunResult(
        Mode mode,
        OrderServer.Endpoint endpoint,
        int requestedTotal,
        int concurrency,
        long elapsedMillis,
        int completed,
        int errors,
        long p50,
        long p95,
        long p99,
        String errorDetail) {

    public double throughput() {
        return elapsedMillis <= 0 ? 0 : completed * 1000.0 / elapsedMillis;
    }

    /** The line under each panel proving both sides ran the same test. */
    public String configSummary() {
        return String.format(Locale.US, "%s · %,d requests · %,d concurrent",
                endpoint.label(), requestedTotal, concurrency);
    }

    /** True when {@code other} was run with the same knobs, so a comparison is fair. */
    public boolean sameConfigAs(RunResult other) {
        return other != null
                && endpoint == other.endpoint
                && requestedTotal == other.requestedTotal
                && concurrency == other.concurrency;
    }

    /**
     * Build a result from raw per-request latencies. Only the first {@code completed}
     * entries of {@code latenciesMillis} are meaningful.
     */
    public static RunResult from(Mode mode, OrderServer.Endpoint endpoint, int requestedTotal,
                                 int concurrency, long elapsedMillis, long[] latenciesMillis,
                                 int completed, int errors, String errorDetail) {
        long[] sorted = Arrays.copyOf(latenciesMillis, completed);
        Arrays.sort(sorted);
        return new RunResult(mode, endpoint, requestedTotal, concurrency, elapsedMillis,
                completed, errors,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                errorDetail);
    }

    private static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
