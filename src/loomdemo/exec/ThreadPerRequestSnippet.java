package loomdemo.exec;

/**
 * The three teaching snippets behind the Thread-per-Request tab (talk slide 14).
 *
 * <p>Like {@link Snippet}, this is real code: it is compiled and run by a child JVM exactly
 * as it appears in the editor, so the presenter can edit it live and the audience is never
 * shown something that only pretends to run.
 *
 * <p>Slide 14 argues that thread-per-request was the <em>good</em> design — one thread runs
 * a whole request, and four things follow. Rather than crowd all four into one program, the
 * tab splits them across snippets, each making its point cleanly, in the same
 * {@code findUser → findOrder → chargeCard} vocabulary the Performance Comparison tab uses:
 *
 * <ul>
 *   <li>{@link #HANDLER} — the sequential handler with a request id carried on a
 *       {@code ThreadLocal}: <strong>sequential, readable code</strong> and
 *       <strong>ThreadLocal context</strong>.</li>
 *   <li>{@link #THREAD_LOCAL} — how that context stays per-request. The same request
 *       chain, with the id stored twice over: a plain {@code static} field beside a
 *       {@code ThreadLocal}, written back to back. Two requests overlap, so one run shows
 *       the id reaching code nobody handed it to, the static field clobbered by the other
 *       request, and both threads holding the same {@code ThreadLocal} object. The
 *       mechanism behind {@link #HANDLER}.</li>
 *   <li>{@link #WHEN_IT_BREAKS} — the same handler, but the payment gateway throws: the
 *       stack trace is the whole request, and the failed request names itself.
 *       <strong>Real stack traces</strong> and <strong>trivial to debug</strong>.</li>
 * </ul>
 *
 * <p>Two conventions are load-bearing, both shared with {@link Snippet}: output lines of the
 * form {@code <thread-name> -> <text>} are tinted per thread by the tab, and {@code public
 * class Demo} is declared first so the child runner detects it as the entry class. Only the
 * worker names {@code http-1}/{@code http-2} ever print, so the tab needs only two colours.
 */
public enum ThreadPerRequestSnippet {

    HANDLER(
            "The Handler",
            "Three services, one thread — and a request id that rides along.",
            """
            /*
             * THREAD-PER-REQUEST — one thread runs the whole request, top to bottom.
             *
             * A request arrives and one thread handles it start to finish: find the user,
             * find their order, charge the card. Each call needs the answer from the one
             * before, so the code reads in the order it runs — no callbacks, no futures.
             *
             * And because one thread owns the whole request, the request id set at the top
             * is readable in every method below without being passed to any of them. That
             * is a ThreadLocal: per-thread context — the same thing SLF4J's MDC and a web
             * framework's request scope are built on.
             *
             * Two requests come in, each on its own worker thread — one thread, one
             * request — so the two colours below are two whole requests.
             */
            public class Demo {

                // (3) THREADLOCAL CONTEXT — one value per thread. Set once when the request
                // arrives, read anywhere below it, because "below" is this thread's stack.
                static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

                public static void main(String[] args) throws InterruptedException {
                    handle("42", "http-1");
                    handle("77", "http-2");
                }

                static void handle(String orderId, String worker) throws InterruptedException {
                    Thread thread = new Thread(() -> serve(orderId), worker);
                    thread.start();
                    thread.join();
                }

                static void serve(String orderId) {
                    REQUEST_ID.set("req-" + orderId);       // set once, at the top of the request
                    OrderService service = new OrderService();
                    try {
                        // (1) SEQUENTIAL, READABLE CODE — each line needs the line above it,
                        // and reads in the order it runs.
                        User user = service.findUser(orderId);
                        Order order = service.findOrder(user);
                        Receipt receipt = service.chargeCard(user, order);
                        log("responded 200  " + receipt.authCode());
                    } finally {
                        REQUEST_ID.remove();
                    }
                }

                // Every line gets the request id for free: no method was handed it, it rode
                // down the stack on the thread.
                static void log(String message) {
                    System.out.println(Thread.currentThread().getName()
                            + "  ->  [" + REQUEST_ID.get() + "]  " + message);
                }
            }

            class OrderService {

                User findUser(String id) {
                    // The request id is right here — and findUser was never handed it. It
                    // came from the ThreadLocal, set three frames up.
                    Demo.log("findUser(" + id + ")");
                    return new User(id, "user-" + id + "@example.com");
                }

                Order findOrder(User user) {
                    Demo.log("findOrder(" + user.id() + ")");
                    return new Order("ord-" + user.id(), "SHIPPED");
                }

                Receipt chargeCard(User user, Order order) {
                    Demo.log("chargeCard(" + order.id() + ")");
                    return new Receipt(order.id(), "auth-" + order.id());
                }
            }

            record User(String id, String email) {}
            record Order(String id, String status) {}
            record Receipt(String orderId, String authCode) {}
            """),

    THREAD_LOCAL(
            "ThreadLocal",
            "One id stored two ways — and only one of them survives a second request.",
            """
            public class Demo {

                public static void main(String[] args) throws InterruptedException {
                    Thread a = new Thread(() -> serve("42"), "http-1");
                    Thread b = new Thread(() -> serve("77"), "http-2");
                    a.start();
                    Thread.sleep(60);          // request 2 arrives while request 1 is in flight
                    b.start();
                    a.join();
                    b.join();
                }

                static void serve(String orderId) {
                    // Set once, at the top of the request. Both fields, same value.
                    RequestContext.staticId = "req-" + orderId;
                    RequestContext.localId.set("req-" + orderId);

                    System.out.println(Thread.currentThread().getName()
                            + "  -> request in.   our ThreadLocal object = @"
                            + Integer.toHexString(System.identityHashCode(RequestContext.localId)));

                    try {
                        new OrderService().checkout(orderId);
                    } finally {
                        RequestContext.localId.remove();   // the locker doesn't empty itself
                    }
                }
            }

            /*
             * Where the request context lives. Two fields, two strategies.
             * This is, in miniature, what SLF4J's MDC and Spring's RequestContextHolder are.
             */
            final class RequestContext {

                static String staticId;                                          // one slot, holds a VALUE

                static final ThreadLocal<String> localId = new ThreadLocal<>();  // one KEY, per-thread values
            }

            /* Ordinary business code. Look at what is NOT in any of these signatures. */
            class OrderService {

                void checkout(String orderId) {
                    User user = findUser(orderId);
                    Order order = findOrder(user);
                    chargeCard(user, order);
                }

                User findUser(String id) {
                    io(100);                                   // the database
                    log("findUser(" + id + ")");
                    return new User(id);
                }

                Order findOrder(User user) {
                    io(100);
                    log("findOrder(" + user.id() + ")");
                    return new Order("ord-" + user.id());
                }

                void chargeCard(User user, Order order) {
                    io(100);
                    log("chargeCard(" + order.id() + ")");
                }

                // Nobody handed this method a request id either. It reads RequestContext.
                void log(String message) {
                    System.out.printf("%-7s  ->  static=[%s]  threadlocal=[%s]   %s%n",
                            Thread.currentThread().getName(),
                            RequestContext.staticId,
                            RequestContext.localId.get(),
                            message);
                }

                private void io(long ms) {
                    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }

            record User(String id) {}
            record Order(String id) {}
            """),

    WHEN_IT_BREAKS(
            "When It Breaks",
            "The gateway is down, and the trace is the whole request.",
            """
            /*
             * WHEN IT BREAKS — the payment gateway times out.
             *
             * The same handler, the same three calls — but the gateway is down and
             * chargeCard throws. Because one thread ran the whole request, the stack trace
             * IS the whole request: the gateway call, the chargeCard that made it, the
             * handler above that, down to the thread. Nothing ran on another thread, so
             * nothing is missing.
             *
             * (2) Real stack trace: you can read exactly where it broke and how it got
             * there. (4) Trivial to debug: the request id from the ThreadLocal is in the
             * 500 line too, so you also know WHICH request failed. The failure tells its
             * own story.
             */
            public class Demo {

                static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

                public static void main(String[] args) throws InterruptedException {
                    handle("42", "http-1");
                }

                static void handle(String orderId, String worker) throws InterruptedException {
                    Thread thread = new Thread(() -> serve(orderId), worker);
                    thread.start();
                    thread.join();
                }

                static void serve(String orderId) {
                    REQUEST_ID.set("req-" + orderId);
                    OrderService service = new OrderService();
                    try {
                        User user = service.findUser(orderId);
                        Order order = service.findOrder(user);
                        Receipt receipt = service.chargeCard(user, order);   // the gateway throws in here
                        log("responded 200  " + receipt.authCode());
                    } catch (Exception failure) {
                        // You know which request (the ThreadLocal), and exactly where and how
                        // it broke (the trace below) — the whole request on one stack.
                        log("responded 500  (" + failure.getMessage() + ")");
                        failure.printStackTrace(System.out);
                    } finally {
                        REQUEST_ID.remove();
                    }
                }

                static void log(String message) {
                    System.out.println(Thread.currentThread().getName()
                            + "  ->  [" + REQUEST_ID.get() + "]  " + message);
                }
            }

            class OrderService {

                User findUser(String id) {
                    Demo.log("findUser(" + id + ")");
                    return new User(id, "user-" + id + "@example.com");
                }

                Order findOrder(User user) {
                    Demo.log("findOrder(" + user.id() + ")");
                    return new Order("ord-" + user.id(), "SHIPPED");
                }

                Receipt chargeCard(User user, Order order) {
                    Demo.log("chargeCard(" + order.id() + ")");
                    String authCode = callPaymentGateway(order);   // blocks — and here it throws
                    return new Receipt(order.id(), authCode);
                }

                private String callPaymentGateway(Order order) {
                    throw new IllegalStateException("payment gateway timeout");
                }
            }

            record User(String id, String email) {}
            record Order(String id, String status) {}
            record Receipt(String orderId, String authCode) {}
            """);

    private final String label;
    private final String caption;
    private final String source;

    ThreadPerRequestSnippet(String label, String caption, String source) {
        this.label = label;
        this.caption = caption;
        this.source = source;
    }

    /** Text on the picker button. */
    public String label() {
        return label;
    }

    /** The one line above the editor that tells the audience what to look for. */
    public String caption() {
        return caption;
    }

    public String source() {
        return source;
    }
}
