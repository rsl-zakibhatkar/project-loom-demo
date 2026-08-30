package loomdemo.exec;

/**
 * The one program behind The Workaround tab: the same failing request, written both ways.
 *
 * <p>A single source with no picker, so this is a holder of constants rather than an enum —
 * the shape {@link DemoSources} uses, not the shape {@link ThreadPerRequestSnippet} uses.
 * Like every other snippet in the app it is real code, compiled and run in a child JVM
 * exactly as it appears on screen; the audience is never shown a trace that was typed out
 * by hand.
 *
 * <p>It is the counterweight to {@link ThreadPerRequestSnippet#WHEN_IT_BREAKS} on the tab
 * before it. That one makes the case <em>for</em> thread-per-request — the trace is the whole
 * request, so the failure tells its own story. This one gives that up and shows the bill: the
 * identical three calls composed out of {@code CompletableFuture} stages, failing at the same
 * gateway, with a trace that can say what broke but not which request it broke for.
 *
 * <p>Two conventions are load-bearing:
 *
 * <ul>
 *   <li>{@code public class Demo} is declared first, so {@code ChildJvmRunner.detectClassName}
 *       finds it, and there is no {@code package} declaration — {@code startCompiled} launches
 *       {@code -cp classDir Demo} and a package would put the class somewhere else.</li>
 *   <li>The program prints {@link #BLOCKING_MARKER} and {@link #ASYNC_MARKER} and nothing else
 *       but the two traces. {@code WorkaroundTab} splits the child's output on those two lines
 *       to decide which panel each trace belongs in, so the literals below and the ones inside
 *       {@link #SOURCE} have to stay in step. Documented rather than enforced, the same call
 *       {@link ThreadPerRequestSnippet} makes about its {@code name -> text} output lines —
 *       and safe here besides, because this source is shown read-only and cannot drift under
 *       a presenter's edit.</li>
 * </ul>
 *
 * <p>The handler methods live on {@code Demo} and the services in classes of their own. That
 * split is what the tab's footer metric counts: an {@code at Demo.} frame is a frame naming
 * the handler, which is to say a frame that answers "which request was this?".
 */
public final class WorkaroundSnippet {

    /** Everything printed after this line, up to {@link #ASYNC_MARKER}, is the blocking trace. */
    public static final String BLOCKING_MARKER = "---- BLOCKING ----";

    /** Everything printed after this line is the async trace. */
    public static final String ASYNC_MARKER = "---- ASYNC ----";

    /** The one line above the code that tells the audience what to look for. */
    public static final String CAPTION =
            "The same three calls, written twice. Both fail at the same gateway — "
                    + "press Run and read what each failure can tell you.";

    public static final String SOURCE = """
            /*
             * THE WORKAROUND — the same request, written twice, failing the same way twice.
             *
             * The payment gateway is down. Below, the identical three calls — findUser,
             * findOrder, chargeCard — are written the two ways a Java server has been
             * written: one thread running the request from top to bottom, and a chain of
             * CompletableFuture stages where no thread is ever allowed to wait.
             *
             * Both throw the same IllegalStateException from the same place. Run it and read
             * the two traces side by side. The question to ask of each is not "what broke?" —
             * both answer that — but "which request broke, and who asked for it?".
             */

            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            import java.util.concurrent.TimeUnit;

            public class Demo {

                public static void main(String[] args) throws Exception {

                    // (1) THREAD-PER-REQUEST — one thread runs the whole request.
                    System.out.println("---- BLOCKING ----");
                    handle("42");

                    // (2) THE WORKAROUND — the same request, composed out of stages.
                    System.out.println("---- ASYNC ----");
                    // The event loop: a handful of threads, none of which may ever wait.
                    ExecutorService eventLoop = Executors.newFixedThreadPool(2);
                    try {
                        serveAsync("42", eventLoop).join();
                    } finally {
                        eventLoop.shutdown();
                    }
                }

                /** A request arrives. Give it a thread, and let that thread do the whole job. */
                static void handle(String orderId) throws InterruptedException {
                    Thread worker = new Thread(() -> serve(orderId), "http-1");
                    worker.start();
                    worker.join();
                }

                static void serve(String orderId) {
                    OrderService service = new OrderService();
                    try {
                        User user = service.findUser(orderId);
                        Order order = service.findOrder(user);
                        Receipt receipt = service.chargeCard(user, order);  // it throws in here
                        System.out.println("responded 200  " + receipt.authCode());
                    } catch (Exception failure) {
                        printTrace(failure);
                    }
                }

                /*
                 * The same three calls. Nothing blocks, so nothing can still be "inside" this
                 * method when the gateway fails — serveAsync only SCHEDULES the work and
                 * returns, long before anything goes wrong. That is why its frame is missing
                 * from the trace: not hidden, not wrapped. It genuinely was not there.
                 *
                 * The two thenCompose calls are nested rather than chained because chargeCard
                 * needs BOTH earlier results, and a CompletableFuture only carries the last
                 * value forward.
                 */
                static CompletableFuture<Void> serveAsync(String orderId, Executor eventLoop) {
                    AsyncOrderService service = new AsyncOrderService(eventLoop);
                    return service.findUser(orderId)
                            .thenCompose(user -> service.findOrder(user)
                                    .thenCompose(order -> service.chargeCard(user, order)))
                            .thenAccept(receipt ->
                                    System.out.println("responded 200  " + receipt.authCode()))
                            .exceptionally(failure -> {
                                printTrace(failure);
                                return null;
                            });
                }

                /*
                 * Print the failure and every cause, frame by frame.
                 *
                 * printStackTrace() would abbreviate the cause down to "... 3 more" — and on
                 * the async side those are exactly the frames worth looking at, so walk the
                 * chain ourselves rather than take the JDK's summary of it.
                 */
                static void printTrace(Throwable failure) {
                    System.out.println(failure);
                    printFrames(failure);
                    Throwable cause = failure.getCause();
                    for (int depth = 0; cause != null && depth < 8; depth++) {
                        System.out.println("Caused by: " + cause);
                        printFrames(cause);
                        cause = cause.getCause();
                    }
                }

                static void printFrames(Throwable throwable) {
                    for (StackTraceElement frame : throwable.getStackTrace()) {
                        System.out.println("\\tat " + frame);
                    }
                }
            }

            /** Calls, and waits. The thread stays on the request the whole way down. */
            class OrderService {

                User findUser(String id) {
                    sleep(40);
                    return new User(id, "user-" + id + "@example.com");
                }

                Order findOrder(User user) {
                    sleep(35);
                    return new Order("order-" + user.id(), "SHIPPED");
                }

                Receipt chargeCard(User user, Order order) {
                    String authCode = callPaymentGateway(user, order);   // blocks, and throws
                    return new Receipt(order.id(), user.email(), authCode);
                }

                private String callPaymentGateway(User user, Order order) {
                    sleep(25);
                    throw new IllegalStateException("payment gateway timeout");
                }

                private static void sleep(long millis) {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            /** Composes, and returns. Every method hands back a future and gets out of the way. */
            class AsyncOrderService {

                private final Executor eventLoop;

                AsyncOrderService(Executor eventLoop) {
                    this.eventLoop = eventLoop;
                }

                CompletableFuture<User> findUser(String id) {
                    return CompletableFuture.supplyAsync(
                            () -> new User(id, "user-" + id + "@example.com"), after(40));
                }

                CompletableFuture<Order> findOrder(User user) {
                    return CompletableFuture.supplyAsync(
                            () -> new Order("order-" + user.id(), "SHIPPED"), after(35));
                }

                CompletableFuture<Receipt> chargeCard(User user, Order order) {
                    return callPaymentGateway(user, order)
                            .thenApply(auth -> new Receipt(order.id(), user.email(), auth));
                }

                private CompletableFuture<String> callPaymentGateway(User user, Order order) {
                    return CompletableFuture.supplyAsync(() -> {
                        throw new IllegalStateException("payment gateway timeout");
                    }, after(25));
                }

                /** "Completes in N ms without holding a thread" — a non-blocking driver. */
                private Executor after(int millis) {
                    return CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS,
                            eventLoop);
                }
            }

            record User(String id, String email) {}
            record Order(String id, String status) {}
            record Receipt(String orderId, String email, String authCode) {}
            """;

    private WorkaroundSnippet() {
    }
}
