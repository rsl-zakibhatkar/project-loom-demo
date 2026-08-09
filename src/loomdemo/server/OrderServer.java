package loomdemo.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import loomdemo.Mode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny order service, embedded in the app, that exists to be slow in an honest way.
 *
 * <p>Every handler does exactly one thing: {@link Thread#sleep} for the endpoint's
 * latency, then return JSON. The sleep stands in for a blocking database call — that is
 * the whole point. What changes between modes is not the handler but the executor
 * underneath it:
 *
 * <ul>
 *   <li>{@link Mode#PAST} — {@code newFixedThreadPool(200)}: 200 requests in flight,
 *       everything else queues.</li>
 *   <li>{@link Mode#FUTURE} — {@code newVirtualThreadPerTaskExecutor()}: as many in
 *       flight as arrive.</li>
 * </ul>
 *
 * <p>Bound to loopback on an ephemeral port, so it never collides with something already
 * running on the presenting machine and never leaves the laptop.
 */
public final class OrderServer {

    /** Backlog request; macOS clamps this to {@code kern.ipc.somaxconn} regardless. */
    private static final int BACKLOG = 1024;

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
    private Mode runningMode;
    private int port;

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized Mode runningMode() {
        return runningMode;
    }

    public synchronized int port() {
        return port;
    }

    /** Start the server for {@code mode}, restarting it if it is already up in the other mode. */
    public synchronized void startFor(Mode mode) throws IOException {
        if (server != null && runningMode == mode) {
            return;
        }
        stop();

        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG);
        for (Endpoint endpoint : Endpoint.values()) {
            created.createContext(endpoint.path(), handlerFor(endpoint.latencyMillis()));
        }

        ExecutorService created_executor = mode == Mode.PAST
                ? Executors.newFixedThreadPool(200)
                : Executors.newVirtualThreadPerTaskExecutor();

        created.setExecutor(created_executor);
        created.start();

        this.server = created;
        this.executor = created_executor;
        this.runningMode = mode;
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
        runningMode = null;
        port = 0;
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
