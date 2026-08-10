package loomdemo.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * {@link OrderService}, written the way the workaround era needs it: nothing blocks.
 *
 * <p>Same three calls, same latency budget, same records. Every method returns immediately
 * with a {@link CompletableFuture} that completes once its share of the wait has elapsed,
 * and no thread is held in the meantime.
 *
 * <p>{@code delayedExecutor} is the honest stand-in for a non-blocking driver — "this
 * completes in N milliseconds without holding a thread" is exactly what an async database
 * client offers, and exactly what {@link OrderService}'s {@code Thread.sleep} cannot do.
 * The continuation then runs on the event loop, which is where a reactive stack would run it.
 */
public final class AsyncOrderService {

    private final Timings timings;
    private final Executor eventLoop;
    private final boolean gatewayDown;

    public AsyncOrderService(Timings timings, Executor eventLoop) {
        this(timings, eventLoop, false);
    }

    /** @see OrderService#OrderService(Timings, boolean) */
    public AsyncOrderService(Timings timings, Executor eventLoop, boolean gatewayDown) {
        this.timings = timings;
        this.eventLoop = eventLoop;
        this.gatewayDown = gatewayDown;
    }

    public CompletableFuture<User> findUser(String id) {
        return CompletableFuture.supplyAsync(
                () -> new User(id, "user-" + id + "@example.com"), after(timings.findUser()));
    }

    public CompletableFuture<Order> findOrder(User user) {
        return CompletableFuture.supplyAsync(
                () -> new Order("order-" + user.id(), user.id(), "SHIPPED"),
                after(timings.findOrder()));
    }

    /**
     * Needs both earlier results. Because a {@code CompletableFuture} only carries the
     * <em>last</em> value forward, the caller cannot reach {@code user} here without either
     * nesting the stages or inventing a type to carry it — which is the cost the comparison
     * tab is trying to show.
     *
     * <p><strong>This layer composes rather than calls, and that is the point.</strong> A
     * service layer whose methods return futures cannot call the layer below it and wait —
     * it has to hand the layer below a continuation. So {@code chargeCard} is not on the
     * stack when the gateway throws; it returned long before, having only <em>scheduled</em>
     * the work. {@link OrderService#chargeCard} calls and waits, so its frame is still there.
     *
     * <p>That is a second boundary on top of the handler's, and each one costs a frame.
     * Writing this the other way — calling {@code callPaymentGateway} inline inside one
     * supplier — would keep the frame, at the price of hand-rolling every layer boundary
     * instead of composing it. Either way you pay; this is the version people actually write.
     */
    public CompletableFuture<Receipt> chargeCard(User user, Order order) {
        return callPaymentGateway(user, order)
                .thenApply(authCode -> new Receipt(order.id(), user.email(), authCode));
    }

    /**
     * The same two jobs as {@link OrderService}'s gateway, split the same way — only the
     * waiting differs. {@code thenApply} above, not {@code thenApplyAsync}: the continuation
     * runs on whichever thread completed this future, which is the event loop, so building
     * a record does not cost another hop.
     */
    private CompletableFuture<String> callPaymentGateway(User user, Order order) {
        return CompletableFuture.supplyAsync(() -> {
            if (gatewayDown) {
                throw new IllegalStateException("payment gateway timeout");
            }
            return "auth-" + order.id();
        }, after(timings.chargeCard()));
    }

    private Executor after(int millis) {
        return CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, eventLoop);
    }
}
