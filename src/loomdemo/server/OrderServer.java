package loomdemo.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import loomdemo.Era;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A tiny order service, embedded in the app, that exists to be slow in an honest way.
 *
 * <p>Every endpoint does exactly one thing: wait out its latency, then return JSON. The
 * wait stands in for a database call — that is the whole point. What changes between eras
 * is how the server survives that wait:
 *
 * <ul>
 *   <li>{@link Era#PAST} — {@code newFixedThreadPool(200)}, blocking handler: 200 requests
 *       in flight, everything else queues.</li>
 *   <li>{@link Era#WORKAROUND} — one thread per core, async handler: the handler registers
 *       a continuation and returns, so no thread is held during the wait. Thousands of
 *       requests overlap on a handful of threads.</li>
 *   <li>{@link Era#PRESENT} — {@code newVirtualThreadPerTaskExecutor()}, the same blocking
 *       handler as PAST: as many in flight as arrive.</li>
 * </ul>
 *
 * <p><strong>Past and present share a handler byte for byte.</strong> Only the workaround
 * needs different code, and that is the argument the comparison tab is making.
 *
 * <p>Bound to loopback on an ephemeral port, so it never collides with something already
 * running on the presenting machine and never leaves the laptop.
 */
public final class OrderServer {

    /** Backlog request; macOS clamps this to {@code kern.ipc.somaxconn} regardless. */
    private static final int BACKLOG = 1024;

    /**
     * The endpoint that always fails, for the tab's "Break it" view.
     *
     * <p>Deliberately <em>not</em> an {@link Endpoint} constant. The tab populates its
     * endpoint dropdown straight from {@code Endpoint.values()}, so a fourth constant would
     * offer the presenter a handler that throws on every request as a target for a
     * 5,000-request load test. It is registered as its own context instead, and only the
     * Break it button ever asks for it.
     *
     * <p>Longest-prefix routing means this wins over the {@code /order/} context.
     */
    public static final String BOOM_PATH = "/order/boom";

    /**
     * Simulated I/O before the failure, on the async side only.
     *
     * <p>Short, because nobody is waiting on a chart here — but not zero. It forces the
     * {@code supplyAsync} stage onto the delayed executor before it throws, which is the
     * entire point of the exhibit: the failure has to happen <em>after</em> the thread hop,
     * or the trace would still be sitting on the handler's own stack.
     */
    private static final int BOOM_LATENCY_MILLIS = 20;

    static {
        // com.sun.net.httpserver keeps at most 200 idle keep-alive connections and slams
        // the rest shut. LoadGenerator deliberately parks one pooled connection per unit
        // of concurrency (up to 2,000), so at anything past 200 the server spends the run
        // closing connections the client is still using — measured at 3,450 errors on a
        // 2,000-concurrency future run with this left at the default. It only bites the
        // future side, where every request really is in flight at once, so it reads as
        // "virtual threads are flaky". Raise the ceiling clear of the highest concurrency
        // the UI offers.
        //
        // ServerConfig reads this in a static initialiser, so it has to be set before the
        // first HttpServer.create; this class is the only thing here that touches the
        // server, so its own static block gets there first.
        setIfAbsent("sun.net.httpserver.maxIdleConnections", "3000");
        setIfAbsent("sun.net.httpserver.idleInterval", "60");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public enum Endpoint {
        NORMAL("GET /order/{id}", "/order/", 100),
        SLOW("GET /order/slow/{id}", "/order/slow/", 300),
        FAST("GET /order/fast/{id}", "/order/fast/", 20);

        private final String label;
        private final String path;
        private final int latencyMillis;

        Endpoint(String label, String path, int latencyMillis) {
            this.label = label;
            this.path = path;
            this.latencyMillis = latencyMillis;
        }

        public String label() {
            return label;
        }

        public String path() {
            return path;
        }

        public int latencyMillis() {
            return latencyMillis;
        }

        /** Longest-prefix routing means {@code /order/slow/} wins over {@code /order/}. */
        public String urlFor(int port, int id) {
            return "http://127.0.0.1:" + port + path + id;
        }

        @Override
        public String toString() {
            return label + "  —  " + latencyMillis + "ms simulated I/O";
        }
    }

    private HttpServer server;
    private ExecutorService executor;
    private Era runningEra;
    private int port;

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized Era runningEra() {
        return runningEra;
    }

    public synchronized int port() {
        return port;
    }

    /** Start the server for {@code era}, restarting it if it is already up in another era. */
    public synchronized void startFor(Era era) throws IOException {
        if (server != null && runningEra == era) {
            return;
        }
        stop();

        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG);

        ExecutorService createdExecutor = switch (era) {
            case PAST -> Executors.newFixedThreadPool(200);
            case WORKAROUND -> Executors.newFixedThreadPool(Era.asyncThreads());
            case PRESENT -> Executors.newVirtualThreadPerTaskExecutor();
        };

        for (Endpoint endpoint : Endpoint.values()) {
            HttpHandler handler = era == Era.WORKAROUND
                    ? asyncHandlerFor(endpoint.latencyMillis(), createdExecutor)
                    : handlerFor(endpoint.latencyMillis());
            created.createContext(endpoint.path(), handler);
        }

        // Same split, same reason: past and present share one, the workaround needs its own.
        created.createContext(BOOM_PATH, era == Era.WORKAROUND
                ? asyncBoomHandler(createdExecutor)
                : boomHandler());

        created.setExecutor(createdExecutor);
        created.start();

        this.server = created;
        this.executor = createdExecutor;
        this.runningEra = era;
        this.port = created.getAddress().getPort();
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        runningEra = null;
        port = 0;
    }

    /*
     * ------------------------------------------------------------------ handler sources
     *
     * What the "Show the handlers" view puts on screen. These are extracts of the two
     * methods directly below them, kept adjacent so they cannot drift far — edit one, edit
     * the other. They are what the audience reads while the numbers are still up, and they
     * are the whole reason the workaround era is in this app: async ties with virtual
     * threads on throughput, so the code is the only thing left to compare.
     */

    /** PAST and PRESENT both run this, unchanged. */
    public static final String BLOCKING_HANDLER_SOURCE = """
            exchange -> {
                try {
                    // The one line that matters: a blocking call for the database.
                    Thread.sleep(latencyMillis);

                    byte[] body = json(exchange, latencyMillis);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    quietly(exchange, 503);
                } catch (Throwable t) {
                    quietly(exchange, 500);
                } finally {
                    exchange.close();
                }
            }
            """;

    /** WORKAROUND runs this. Same server, same endpoint, same simulated database. */
    public static final String ASYNC_HANDLER_SOURCE = """
            exchange -> CompletableFuture
                    .supplyAsync(
                            () -> json(exchange, latencyMillis),   // the database call
                            CompletableFuture.delayedExecutor(
                                    latencyMillis, MILLISECONDS, eventLoop))
                    .thenAccept(body -> respond(exchange, 200, body))
                    .exceptionally(failure -> {
                        respond(exchange, 500, null);
                        return null;
                    })
                    .whenComplete((ignored, failure) -> exchange.close());

            // ...plus the response write, which the blocking version got for free
            // from try-with-resources:

            static void respond(HttpExchange exchange, int status, byte[] body) {
                try {
                    if (body == null) {
                        exchange.sendResponseHeaders(status, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException e) {
                    // Nowhere to throw to. There is no caller left on this stack, and
                    // the stack trace you would get names a pool worker, not the
                    // request that failed.
                }
            }
            """;

    public static String handlerSourceFor(Era era) {
        return era == Era.WORKAROUND ? ASYNC_HANDLER_SOURCE : BLOCKING_HANDLER_SOURCE;
    }

    /**
     * Lines of actual code, ignoring blanks and comments.
     *
     * <p>Counting comments would let the async version look bad for the wrong reason —
     * its comments are there to explain it, which is itself the point, but the figure on
     * the panel should be code the audience cannot argue with.
     */
    public static int handlerLineCount(Era era) {
        return (int) handlerSourceFor(era).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("//"))
                .count();
    }

    private static HttpHandler handlerFor(int latencyMillis) {
        return exchange -> {
            try {
                // The one line that matters: a blocking call standing in for the database.
                Thread.sleep(latencyMillis);

                byte[] body = json(exchange, latencyMillis);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                quietly(exchange, 503);
            } catch (Throwable t) {
                quietly(exchange, 500);
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * The workaround era: nothing blocks. The handler registers a continuation and returns
     * immediately, so its thread is free before the simulated database call has even
     * started. {@code delayedExecutor} is the honest stand-in for a non-blocking driver —
     * "this completes in N ms without holding a thread".
     *
     * <p>The same small pool parses requests and writes responses, which is what a reactive
     * event loop actually is, and what makes the headline true: a handful of threads doing
     * what two hundred could not.
     */
    private static HttpHandler asyncHandlerFor(int latencyMillis, Executor eventLoop) {
        return exchange -> CompletableFuture
                .supplyAsync(() -> json(exchange, latencyMillis),
                        CompletableFuture.delayedExecutor(
                                latencyMillis, TimeUnit.MILLISECONDS, eventLoop))
                .thenAccept(body -> respond(exchange, 200, body))
                .exceptionally(failure -> {
                    respond(exchange, 500, null);
                    return null;
                })
                .whenComplete((ignored, failure) -> exchange.close());
    }

    // ------------------------------------------------------------------ break it

    /*
     * The same failure, reached the same way, through both handler shapes. What differs is
     * what you can read afterwards.
     *
     * Measured on this JDK (21.0.12): the blocking trace carries 12 frames, 3 of them ours
     * and 6 of them the server's own request path — Filter$Chain.doFilter, AuthFilter,
     * ServerImpl$Exchange.run. The async trace carries 10 frames and NONE of the request
     * path: it bottoms out at AsyncSupply.run on a pool worker.
     *
     * Both traces name callPaymentGateway. The async one only does so under "Caused by:",
     * and the tab shows that section rather than hiding it — "just call getCause()" is a
     * fair objection and the exhibit has to survive it. What getCause() cannot give back is
     * the request: it is not on that stack at all, at any depth.
     */

    /** PAST and PRESENT share this one, exactly as they share {@link #handlerFor}. */
    private static HttpHandler boomHandler() {
        return exchange -> {
            try {
                loadOrder(idFrom(exchange));
                respond(exchange, 200, null);   // never reached
            } catch (Throwable failure) {
                respondTrace(exchange, failure);
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * The workaround era's version. {@code loadOrder} is called <em>inside</em> the
     * {@code supplyAsync} supplier, so it runs on the delayed executor and the thread hop
     * has already happened when it throws. Failing any earlier — in the handler body, before
     * the stage — would leave the handler's own frame on the stack and prove nothing.
     */
    private static HttpHandler asyncBoomHandler(Executor eventLoop) {
        return exchange -> CompletableFuture
                .supplyAsync(() -> loadOrder(idFrom(exchange)),
                        CompletableFuture.delayedExecutor(
                                BOOM_LATENCY_MILLIS, TimeUnit.MILLISECONDS, eventLoop))
                .thenAccept(order -> respond(exchange, 200, null))
                .exceptionally(failure -> {
                    respondTrace(exchange, failure);
                    return null;
                })
                .whenComplete((ignored, failure) -> exchange.close());
    }

    /*
     * Two methods rather than one, so there are real frames to lose. A single throwing
     * method would give a trace so short that "which frames survived" would not be a
     * question worth asking.
     */

    private static String loadOrder(String id) {
        return callPaymentGateway(id);
    }

    private static String callPaymentGateway(String id) {
        throw new IllegalStateException("payment gateway timeout");
    }

    /**
     * Serialise the failure and send it back as the response body.
     *
     * <p>{@code printStackTrace} rather than anything hand-rolled: the {@code Caused by:}
     * section and the JDK's own {@code ... N more} elision have to be the real ones, or the
     * exhibit is just this app's opinion about stack traces.
     */
    private static void respondTrace(HttpExchange exchange, Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        respond(exchange, 500, text.toString().getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
    }

    private static String idFrom(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : "0";
    }

    /** Where the Break it button sends its one request. */
    public static String boomUrl(int port) {
        return "http://127.0.0.1:" + port + BOOM_PATH;
    }

    /** The response write the blocking handler got for free from try-with-resources. */
    private static void respond(HttpExchange exchange, int status, byte[] body) {
        respond(exchange, status, body, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body,
                                String contentType) {
        try {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            // Nowhere to throw to: there is no caller left on this stack.
        }
    }

    private static byte[] json(HttpExchange exchange, int latencyMillis) {
        String path = exchange.getRequestURI().getPath();
        int slash = path.lastIndexOf('/');
        String id = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : "0";
        return ("{\"orderId\":\"" + id + "\",\"status\":\"SHIPPED\",\"dbLatencyMs\":"
                + latencyMillis + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static void quietly(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // client is already gone
        }
    }
}
