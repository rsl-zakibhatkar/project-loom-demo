package loomdemo.server;

/**
 * How an endpoint's latency budget is shared between the three services.
 *
 * <p>The budget is <em>split</em>, never added to. An endpoint advertised as 100&nbsp;ms of
 * simulated I/O still takes 100&nbsp;ms per request after the rewrite — it just spends it in
 * three calls instead of one. That keeps every throughput number already measured, printed
 * in the README and spoken on stage true, and keeps the chart looking the same mid-talk.
 */
public record Timings(int findUser, int findOrder, int chargeCard) {

    /**
     * Split a budget 40/35/25, with the remainder going to the last slice so the three
     * always sum to exactly {@code totalMillis}.
     *
     * <p>Uneven on purpose: three equal waits look like a loop, and the point is three
     * different calls to three different services.
     */
    public static Timings split(int totalMillis) {
        int user = Math.round(totalMillis * 0.40f);
        int order = Math.round(totalMillis * 0.35f);
        return new Timings(user, order, totalMillis - user - order);
    }

    public int total() {
        return findUser + findOrder + chargeCard;
    }
}
