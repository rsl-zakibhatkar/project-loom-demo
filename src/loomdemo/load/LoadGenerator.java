package loomdemo.load;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import loomdemo.Era;
import loomdemo.server.OrderServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires N requests at the embedded server at a fixed concurrency and records the latency
 * of every one.
 *
 * <p><strong>The client always uses virtual threads, in every era.</strong> That is
 * deliberate and it is the only thing that makes the comparison mean anything: if the load
 * generator itself ran on a bounded platform-thread pool, the past-era numbers would be
 * measuring the client's own queueing rather than the server's. One virtual thread per
 * request, a semaphore holding concurrency at the configured level, and the only variable
 * left between runs is how the server handles blocking.
 *
 * <p>Each run has two phases:
 *
 * <ol>
 *   <li><b>Warm-up</b> (untimed) — open and park {@code concurrency} keep-alive
 *       connections, gently enough not to overflow the OS accept queue.</li>
 *   <li><b>Measure</b> (timed) — the real run, reusing those pooled connections.</li>
 * </ol>
 *
 * <p>The warm-up exists because of two artefacts that would otherwise be charged to the
 * server: macOS caps the listen backlog at {@code kern.ipc.somaxconn} (128) and resets
 * connections that overflow it, and a cold JIT makes the first few hundred requests
 * slower than the rest. Neither has anything to do with virtual threads, and both would
 * land hardest on the two sides that keep every request in flight at once — virtual
 * threads and async. A fresh {@link HttpClient} is built per run so a pool warmed by the
 * previous era cannot flatter the next one.
 */
public final class LoadGenerator {

    /** Long enough that a genuinely saturated server still finishes; short enough to bound a bad run. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /** Chart points to aim for across the whole run. */
    private static final int TARGET_SAMPLES = 250;

    /**
     * New connections per second during warm-up.
     *
     * <p>Fast enough that the whole opening wave is still in flight when the last one
     * connects — that is what makes the pool actually grow to {@code concurrency} instead
     * of recycling a handful of connections — but not so fast that the accept queue
     * overflows while the server is otherwise idle. Warm-up failures are retried and never
     * counted, so erring high here is cheap.
     */
    private static final double WARMUP_CONNECTS_PER_SECOND = 6000.0;

    /** A connection refused during warm-up is expected; give it a couple of goes. */
    private static final int WARMUP_ATTEMPTS = 4;

    /** Let the freshly-opened connections settle into the client's idle pool. */
    private static final long WARMUP_SETTLE_MILLIS = 150;

    public enum Phase {WARMUP, MEASURING}

    public record Sample(double elapsedSeconds, long latencyMillis) {
    }

    /** All callbacks are delivered on the JavaFX application thread. */
    public interface Listener {
        void onSamples(List<Sample> samples);

        void onProgress(Phase phase, int completed, int total, long elapsedMillis);

        void onDone(RunResult result);

        void onStopped(int completed);

        void onError(String message);
    }

    private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger completedCount = new AtomicInteger();

    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile Phase phase = Phase.WARMUP;
    private volatile ExecutorService taskExecutor;
    private volatile HttpClient client;
    private Timeline drainTimeline;
    private long measureStartNanos;

    public boolean isRunning() {
        return running;
    }

    public void start(Era era, int port, OrderServer.Endpoint endpoint,
                      int totalRequests, int concurrency, Listener listener) {
        if (running) {
            listener.onError("A load test is already running.");
            return;
        }
        running = true;
        cancelled = false;
        phase = Phase.WARMUP;
        samples.clear();
        completedCount.set(0);
        measureStartNanos = System.nanoTime();

        Thread orchestrator = new Thread(
                () -> runLoad(era, port, endpoint, totalRequests, concurrency, listener),
                "load-generator");
        orchestrator.setDaemon(true);
        orchestrator.start();

        drainTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(100), e -> {
            drainSamples(listener);
            listener.onProgress(phase, completedCount.get(), totalRequests, elapsedMillis());
        }));
        drainTimeline.setCycleCount(Animation.INDEFINITE);
        drainTimeline.play();
    }

    private void runLoad(Era era, int port, OrderServer.Endpoint endpoint,
                         int totalRequests, int concurrency, Listener listener) {
        HttpClient httpClient = HttpClient.newBuilder()
                // com.sun.net.httpserver speaks 1.1 only; pinning it also stops HTTP/2
                // multiplexing from quietly redefining what "concurrency" means.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        client = httpClient;

        try {
            warmUp(httpClient, port, concurrency);
            if (cancelled) {
                teardown(httpClient);
                finishOnFxThread(() -> listener.onStopped(0));
                return;
            }

            phase = Phase.MEASURING;
            RunResult result = measure(httpClient, era, port, endpoint,
                    totalRequests, concurrency);
            boolean wasCancelled = cancelled;
            int completed = completedCount.get();
            teardown(httpClient);

            if (wasCancelled) {
                finishOnFxThread(() -> listener.onStopped(completed));
            } else {
                finishOnFxThread(() -> listener.onDone(result));
            }
        } catch (Throwable t) {
            teardown(httpClient);
            finishOnFxThread(() -> listener.onError("Load test failed: " + t));
        }
    }

    /**
     * Open {@code connections} keep-alive connections and let them fall idle into the
     * client's pool.
     *
     * <p>Deliberately aimed at the <em>slowest</em> endpoint. A connection only counts
     * towards the pool if it is still busy when the next one opens: warming against a
     * 20ms endpoint just recycles the same few connections over and over and leaves the
     * pool tiny, which defeats the entire point. 300ms of held-open request is what makes
     * the pool grow to full concurrency.
     */
    private void warmUp(HttpClient httpClient, int port, int connections) {
        OrderServer.Endpoint warmEndpoint = OrderServer.Endpoint.SLOW;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            taskExecutor = executor;
            for (int i = 0; i < connections; i++) {
                final int id = i;
                executor.submit(() -> {
                    if (cancelled) {
                        return null;
                    }
                    long rampDelayMillis = (long) (id / WARMUP_CONNECTS_PER_SECOND * 1000.0);
                    if (rampDelayMillis > 0) {
                        Thread.sleep(rampDelayMillis);
                    }
                    for (int attempt = 0; attempt < WARMUP_ATTEMPTS && !cancelled; attempt++) {
                        try {
                            httpClient.send(get(warmEndpoint, port, id),
                                    HttpResponse.BodyHandlers.discarding());
                            return null;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        } catch (Exception retryable) {
                            Thread.sleep(25);
                        }
                    }
                    return null;
                });
            }
        }
        try {
            Thread.sleep(WARMUP_SETTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RunResult measure(HttpClient httpClient, Era era, int port,
                              OrderServer.Endpoint endpoint, int totalRequests, int concurrency) {
        long[] latencies = new long[totalRequests];
        AtomicInteger errors = new AtomicInteger();
        Map<String, AtomicInteger> errorKinds = new ConcurrentHashMap<>();
        Semaphore gate = new Semaphore(concurrency);
        int sampleEvery = Math.max(1, totalRequests / TARGET_SAMPLES);

        measureStartNanos = System.nanoTime();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        taskExecutor = executor;
        try (executor) {
            for (int i = 0; i < totalRequests; i++) {
                final int id = i;
                executor.submit(() -> {
                    fireOne(httpClient, endpoint, port, id, gate, latencies, errors,
                            errorKinds, sampleEvery);
                    return null;
                });
            }
        } // close() waits for every task

        long elapsed = (System.nanoTime() - measureStartNanos) / 1_000_000L;
        return RunResult.from(era, endpoint, totalRequests, concurrency, elapsed,
                latencies, completedCount.get(), errors.get(), describe(errorKinds));
    }

    private void fireOne(HttpClient httpClient, OrderServer.Endpoint endpoint, int port, int id,
                         Semaphore gate, long[] latencies, AtomicInteger errors,
                         Map<String, AtomicInteger> errorKinds, int sampleEvery) {
        if (cancelled) {
            return;
        }
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;   // a stop, not a failure — don't count it as an error
        }
        try {
            if (cancelled) {
                return;
            }
            long t0 = System.nanoTime();
            HttpResponse<Void> response =
                    httpClient.send(get(endpoint, port, id), HttpResponse.BodyHandlers.discarding());
            long latencyMillis = (System.nanoTime() - t0) / 1_000_000L;

            if (response.statusCode() == 200) {
                int slot = completedCount.getAndIncrement();
                if (slot < latencies.length) {
                    latencies[slot] = latencyMillis;
                }
                if (slot % sampleEvery == 0) {
                    samples.add(new Sample(
                            (System.nanoTime() - measureStartNanos) / 1_000_000_000.0,
                            latencyMillis));
                }
            } else {
                errors.incrementAndGet();
                record(errorKinds, "HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (!cancelled) {
                errors.incrementAndGet();
                record(errorKinds, t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
        } finally {
            gate.release();
        }
    }

    private static HttpRequest get(OrderServer.Endpoint endpoint, int port, int id) {
        return HttpRequest.newBuilder(URI.create(endpoint.urlFor(port, id)))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    private static void record(Map<String, AtomicInteger> kinds, String kind) {
        kinds.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Most common failure first — surfaced as a tooltip on the errors figure. */
    private static String describe(Map<String, AtomicInteger> kinds) {
        return kinds.entrySet().stream()
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, AtomicInteger> e) -> e.getValue().get()).reversed())
                .limit(3)
                .map(e -> e.getKey() + " ×" + e.getValue().get())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private void drainSamples(Listener listener) {
        if (samples.isEmpty()) {
            return;
        }
        List<Sample> batch = new ArrayList<>();
        Sample sample;
        while (batch.size() < 500 && (sample = samples.poll()) != null) {
            batch.add(sample);
        }
        listener.onSamples(batch);
    }

    private void finishOnFxThread(Runnable action) {
        Platform.runLater(() -> {
            running = false;
            if (drainTimeline != null) {
                drainTimeline.stop();
                drainTimeline = null;
            }
            action.run();
        });
    }

    private void teardown(HttpClient httpClient) {
        taskExecutor = null;
        client = null;
        try {
            httpClient.shutdownNow();
            httpClient.close();
        } catch (Throwable ignored) {
            // nothing useful to do while tearing down
        }
    }

    /** Abandon the run. The stats panel keeps whatever complete run it was already showing. */
    public void stop() {
        if (!running) {
            return;
        }
        cancelled = true;
        ExecutorService executor = taskExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        HttpClient current = client;
        if (current != null) {
            try {
                current.shutdownNow();
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private long elapsedMillis() {
        return (System.nanoTime() - measureStartNanos) / 1_000_000L;
    }
}
