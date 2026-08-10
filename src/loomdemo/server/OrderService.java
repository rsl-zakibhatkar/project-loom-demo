package loomdemo.server;

/**
 * Three dummy services that do nothing but wait.
 *
 * <p>They exist so the handler on screen looks like a controller the audience has written:
 * find a user, find their order, charge the card, each call depending on the one before it.
 * There is no database, no gateway and no email — every method sleeps out its share of the
 * endpoint's latency budget and returns a record. The presenter says so out loud; the point
 * is never what these do, it is what the <em>handler</em> around them has to look like.
 *
 * <p>The timings are held here rather than passed in, so the handler source reads
 * {@code service.findUser(id)} with no latency argument cluttering the line the audience is
 * meant to be reading. That is also how a Spring developer expects to see it: an injected
 * service, configured elsewhere.
 *
 * @see AsyncOrderService the same three calls, written the way the workaround era needs them
 */
public final class OrderService {

    private final Timings timings;
    private final boolean gatewayDown;

    public OrderService(Timings timings) {
        this(timings, false);
    }

    /**
     * @param gatewayDown when true, {@link #callPaymentGateway} throws. Only the Break it
     *                    endpoint builds one of these, and it does so precisely so the stack
     *                    trace on screen names the same methods the audience just read in
     *                    the handler — not a second set of invented ones.
     */
    public OrderService(Timings timings, boolean gatewayDown) {
        this.timings = timings;
        this.gatewayDown = gatewayDown;
    }

    public User findUser(String id) {
        pause(timings.findUser());
        return new User(id, "user-" + id + "@example.com");
    }

    public Order findOrder(User user) {
        pause(timings.findOrder());
        return new Order("order-" + user.id(), user.id(), "SHIPPED");
    }

    /**
     * Needs <em>both</em> earlier results, which is the whole reason the async version of
     * this chain cannot be written flat.
     */
    public Receipt chargeCard(User user, Order order) {
        pause(timings.chargeCard());
        return callPaymentGateway(user, order);
    }

    /*
     * A second method under chargeCard so a failure has a real call chain inside the stage,
     * rather than a trace one frame deep. Both frames survive the async hop, and should —
     * everything a stage calls synchronously stays on its stack. What does not survive is
     * everything BELOW the stage boundary.
     */
    private Receipt callPaymentGateway(User user, Order order) {
        if (gatewayDown) {
            throw new IllegalStateException("payment gateway timeout");
        }
        return new Receipt(order.id(), user.email(), "PAID");
    }

    /**
     * The simulated I/O.
     *
     * <p>Unchecked on the way out, so the handler needs one {@code catch} rather than two and
     * the line the audience reads stays a plain method call. The interrupt flag is restored
     * first — the server's executor interrupts its workers on shutdown, and swallowing that
     * would leave threads running after {@code stop()}.
     */
    private static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
