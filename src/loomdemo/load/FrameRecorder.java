package loomdemo.load;

import javafx.application.Platform;
import loomdemo.Era;
import loomdemo.Shape;
import loomdemo.server.Order;
import loomdemo.server.OrderService;
import loomdemo.server.Timings;
import loomdemo.server.User;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Runs the order service a handful of times and writes down where every request was, on
 * which platform thread, on both sides of every blocking call.
 *
 * <p>The same three services the Performance Comparison tab hits — {@code findUser},
 * {@code findOrder}, {@code chargeCard}, sharing the endpoint's latency the same 40/35/25 way
 * — called directly rather than over HTTP. The server is not the subject here: one request is,
 * and a load generator in front of it would only add frames between the audience and the thing
 * they are meant to watch. {@code OrderServer} is left alone for the same reason its own
 * javadoc gives: past and present share a handler byte for byte, and a third instrumented
 * handler shape in that file would quietly cost it that argument.
 *
 * <p>Under {@link Shape#PLATFORM} the pool is sized to the request count, one platform thread
 * each. That is not the Performance Comparison tab's pool of 200 and is not meant to be —
 * nothing should queue here, because queueing is a different argument. What is left when
 * nothing queues is exactly what one blocking call costs.
 *
 * <p>Under {@link Shape#PINNED} the three calls happen inside a monitor the request owns
 * alone, so nothing contends and the only thing left to measure is the carrier being unable
 * to leave. Two extra records come out of that run and out of no other: the
 * {@link PinSample} sweep below, which is the evidence, and a {@link FrameRun.Probe} — one
 * more virtual thread, submitted last, that takes no lock and does no work, so the board can
 * show how long a request needing nothing at all had to wait for a carrier.
 *
 * <p>Callbacks land on the FX thread. There is no batching queue of the kind
 * {@link LoadGenerator} needs: this emits at most one progress callback per request, so a
 * couple of dozen for a whole run.
 */
public final class FrameRecorder {

    /** Which request's stack gets photographed mid-wait. Fixed, so the exhibit is repeatable. */
    private static final int WATCHED_REQUEST = 0;

    /**
     * A backstop, not the usual way the sweep ends.
     *
     * <p>The watcher is a daemon and is interrupted in {@code record}'s {@code finally}, which
     * is what normally stops it. This only matters if that never happens, and it has to
     * outlast the longest run the tab can ask for rather than the old five seconds, because
     * the sweep now runs for the whole recording instead of stopping at the first photograph.
     */
    private static final long WATCH_BUDGET_MILLIS = 20_000;

    /**
     * How often every recorded thread is looked at.
     *
     * <p>Fast enough that the shortest wait the tab can produce — the 20 ms endpoint split
     * three ways, so five to eight milliseconds — still gets sampled more than once. The
     * sweep only calls {@code Thread.getState()} and {@code toString()}, neither of which
     * needs a safepoint; the one expensive {@code getStackTrace()} is still taken once a run.
     */
    private static final long SAMPLE_INTERVAL_MILLIS = 2;

    public interface Listener {
        void onProgress(int completed, int total);

        void onDone(FrameRun run);

        void onStopped(int completed);

        void onError(String message);
    }

    private volatile boolean running;
    private volatile boolean stopRequested;
    private final AtomicReference<ExecutorService> current = new AtomicReference<>();

    /** Bumped by every start and by {@link #stop()}, so an abandoned run's reply is dropped. */
    private int generation;

    public boolean isRunning() {
        return running;
    }

    public void start(Shape shape, int requests, int latencyMillis, Listener listener) {
        if (running) {
            return;
        }
        running = true;
        stopRequested = false;
        int runGeneration = ++generation;

        Thread driver = new Thread(
                () -> record(shape, requests, latencyMillis, listener, runGeneration),
                "frame-record");
        driver.setDaemon(true);
        driver.start();
    }

    /**
     * Abandon the run. The tasks are interrupted; {@code OrderService.pause} restores the
     * interrupt flag and throws, and each task still records its END mark on the way out, so
     * a stopped run leaves a consistent record rather than a half-written one.
     */
    public void stop() {
        stopRequested = true;
        ExecutorService executor = current.get();
        if (executor != null) {
            executor.shutdownNow();
        }
        running = false;
        // Deliberately NOT bumping the generation. The guard exists to drop replies from a
        // run that has been SUPERSEDED, and the next start() bumps it for exactly that. Doing
        // it here would filter out this run's own onStopped, which is the callback that tells
        // the presenter how far it got before they pressed the button.
    }

    // ------------------------------------------------------------------ the run

    private void record(Shape shape, int requests, int latencyMillis, Listener listener,
                        int runGeneration) {
        Queue<MountEvent> events = new ConcurrentLinkedQueue<>();
        Queue<PinSample> samples = new ConcurrentLinkedQueue<>();
        AtomicReferenceArray<Thread> threads = new AtomicReferenceArray<>(requests);
        AtomicReference<FrameRun.ParkedStack> parked = new AtomicReference<>();
        AtomicReference<FrameRun.Probe> probe = new AtomicReference<>();
        AtomicInteger completed = new AtomicInteger();
        OrderService service = new OrderService(Timings.split(latencyMillis));

        // One monitor per request, and no request ever touches another's. That is the whole
        // reason the guarded run proves anything: with a shared lock the stall would be
        // ordinary mutual exclusion — the lesson Snippet.ONE_OVEN_LOCKED already teaches on
        // platform threads — and the room would be right to say so.
        Object[] monitors = new Object[requests];
        for (int i = 0; i < requests; i++) {
            monitors[i] = new Object();
        }

        Thread watcher = new Thread(() -> watch(shape, threads, parked, samples), "frame-watch");
        watcher.setDaemon(true);
        watcher.start();

        try {
            ExecutorService executor = shape.era() == Era.PRESENT
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(requests, platformFactory());
            current.set(executor);

            // close() waits for every task, which is exactly the join this needs.
            try (executor) {
                for (int i = 0; i < requests; i++) {
                    int request = i;
                    executor.submit(() -> runOne(shape, request, monitors[request], service,
                            events, threads, completed, requests, listener, runGeneration));
                }
                submitProbe(shape, executor, probe);
            }
        } catch (Throwable failure) {
            deliver(runGeneration, () -> {
                running = false;
                listener.onError("Could not record the run: " + failure);
            });
            return;
        } finally {
            current.set(null);
            watcher.interrupt();
        }

        int done = completed.get();
        if (stopRequested) {
            deliver(runGeneration, () -> {
                running = false;
                listener.onStopped(done);
            });
            return;
        }

        FrameRun run = new FrameRun(shape, requests, latencyMillis, new ArrayList<>(events),
                new ArrayList<>(samples), parked.get(), probe.get());
        deliver(runGeneration, () -> {
            running = false;
            listener.onDone(run);
        });
    }

    private void runOne(Shape shape, int request, Object monitor, OrderService service,
                        Queue<MountEvent> events, AtomicReferenceArray<Thread> threads,
                        AtomicInteger completed, int requests, Listener listener,
                        int runGeneration) {
        Thread self = Thread.currentThread();
        if (shape.era() == Era.PRESENT) {
            // Virtual threads from the per-task executor are unnamed, and an unnamed thread
            // prints as VirtualThread[#23] with nothing to tie it to a request. Naming it
            // here costs nothing and makes every label on the board readable.
            self.setName("req-" + request);
        }
        threads.set(request, self);
        String label = labelOf(self);

        events.add(mark(request, label, MountEvent.Kind.START, ""));
        try {
            if (shape.guarded()) {
                // What a library synchronizing on a connection object looks like from the
                // outside. Held across all three calls, and owned by nobody else.
                synchronized (monitor) {
                    callAll(request, label, service, events);
                }
            } else {
                callAll(request, label, service, events);
            }
        } catch (RuntimeException interruptedOrWorse) {
            // Only reachable via Stop, which interrupts the sleep. The END mark below still
            // closes this request's record.
        } finally {
            events.add(mark(request, label, MountEvent.Kind.END, ""));
            int done = completed.incrementAndGet();
            deliver(runGeneration, () -> listener.onProgress(done, requests));
        }
    }

    /**
     * The six marks and the three calls between them, identical in every shape.
     *
     * <p>Extracted so the only difference between a pinned run and a plain one is the
     * {@code synchronized} its caller wraps around this — which is the claim the tab makes,
     * and it should be true of the code as literally as it is of the board.
     */
    private static void callAll(int request, String label, OrderService service,
                                Queue<MountEvent> events) {
        events.add(mark(request, label, MountEvent.Kind.CALL, "findUser"));
        User user = service.findUser(String.valueOf(request));
        events.add(mark(request, label, MountEvent.Kind.RETURN, "findUser"));

        events.add(mark(request, label, MountEvent.Kind.CALL, "findOrder"));
        Order order = service.findOrder(user);
        events.add(mark(request, label, MountEvent.Kind.RETURN, "findOrder"));

        events.add(mark(request, label, MountEvent.Kind.CALL, "chargeCard"));
        service.chargeCard(user, order);
        events.add(mark(request, label, MountEvent.Kind.RETURN, "chargeCard"));
    }

    /**
     * One more virtual thread, submitted after every request and asking for nothing.
     *
     * <p>It takes no monitor and does no work, so the only thing between it and finishing is
     * getting a carrier at all. That makes it the honest version of slide 40's "millions
     * ready to run, no carrier free": when the carriers are pinned it waits out most of the
     * run for zero milliseconds of work, and when they are not it lands immediately.
     *
     * <p>Virtual shapes only. A platform pool sized to its own load has nothing spare either,
     * but that is queueing — a different argument, and one the Performance Comparison tab
     * already makes.
     */
    private static void submitProbe(Shape shape, ExecutorService executor,
                                    AtomicReference<FrameRun.Probe> out) {
        if (shape.era() != Era.PRESENT) {
            return;
        }
        long submitted = System.nanoTime();
        executor.submit(() -> {
            Thread.currentThread().setName("health");
            out.set(new FrameRun.Probe(submitted, System.nanoTime(),
                    carrierOf(Thread.currentThread())));
        });
    }

    /**
     * Watch the run from outside: photograph one stack mid-wait, and sample everybody for
     * pinning.
     *
     * <p>The photograph is the exhibit that cannot be argued with — the frames are all there,
     * and under plain virtual threads there is no {@code @carrier} in the thread's own
     * {@code toString()}, because nothing is running it. A sample is only accepted when the
     * thread really is in the state being claimed, so a virtual thread caught mid-mount is
     * discarded and the loop tries again rather than reporting a carrier that is about to
     * disappear.
     *
     * <p>The sweep alongside it is what makes pinning a measurement instead of a setting.
     * {@code TIMED_WAITING} <em>and</em> a carrier is a state only a pinned virtual thread can
     * be in: one that unmounted has no carrier, and one that is merely running is
     * {@code RUNNABLE}. So a single {@link PinSample} proves the pin, and an empty sweep
     * proves there was none — neither answer comes from having been told which button was
     * pressed.
     */
    private void watch(Shape shape, AtomicReferenceArray<Thread> threads,
                       AtomicReference<FrameRun.ParkedStack> out, Queue<PinSample> samples) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WATCH_BUDGET_MILLIS);
        while (System.nanoTime() < deadline && !stopRequested) {
            for (int request = 0; request < threads.length(); request++) {
                Thread watched = threads.get(request);
                if (watched == null || watched.getState() != Thread.State.TIMED_WAITING) {
                    continue;
                }
                String carrier = carrierOf(watched);

                if (watched.isVirtual() && carrier != null) {
                    samples.add(new PinSample(System.nanoTime(), request, carrier));
                }

                if (request == WATCHED_REQUEST && out.get() == null
                        && wanted(shape, watched, carrier)) {
                    StackTraceElement[] frames = watched.getStackTrace();
                    if (frames.length > 0) {
                        out.set(snapshot(shape, watched, carrier, frames));
                    }
                }
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The state each shape's photograph is supposed to show, and will not be taken without. */
    private static boolean wanted(Shape shape, Thread watched, String carrier) {
        return switch (shape) {
            case PLATFORM -> carrier != null;
            case VIRTUAL -> carrier == null;
            case PINNED -> watched.isVirtual() && carrier != null;
        };
    }

    private static FrameRun.ParkedStack snapshot(Shape shape, Thread watched, String carrier,
                                                 StackTraceElement[] frames) {
        StringBuilder text = new StringBuilder(watched.toString()).append('\n');
        for (StackTraceElement frame : frames) {
            text.append("\tat ").append(frame).append('\n');
        }
        boolean held = shape != Shape.VIRTUAL;
        String holder = switch (shape) {
            case VIRTUAL -> "none";
            case PLATFORM -> watched.getName() + "  (" + watched.getState() + ")";
            case PINNED -> FrameRun.shortCarrier(carrier) + "  (" + watched.getState() + ")";
        };
        return new FrameRun.ParkedStack(WATCHED_REQUEST, watched.toString(), text.toString(),
                holder, held);
    }

    // ------------------------------------------------------------------ thread reading

    /**
     * The carrier actually executing {@code thread}, or null when nothing is.
     *
     * <p>Read out of {@link Thread#toString()}, which is public information and needs no
     * internal API: a mounted virtual thread prints
     * {@code VirtualThread[#23,req-0]/runnable@ForkJoinPool-1-worker-1} and a parked one
     * prints {@code VirtualThread[#23,req-0]/timed_waiting} with no {@code @} at all. A
     * platform thread carries itself, so it answers with its own name.
     *
     * <p>A <em>pinned</em> virtual thread is the case that makes this worth reading twice:
     * it prints {@code VirtualThread[#23,req-0]/timed_waiting@ForkJoinPool-1-worker-6} —
     * waiting, and still holding a carrier, in one line.
     *
     * <p>If some other runtime ever prints a shape this cannot read, the fallback is the
     * literal {@code "carrier?"} rather than a guess — a wrong carrier name would make the
     * board tell a story that did not happen.
     */
    public static String carrierOf(Thread thread) {
        if (!thread.isVirtual()) {
            return thread.getName();
        }
        String text = thread.toString();
        int at = text.lastIndexOf('@');
        if (at < 0) {
            return null;                       // unmounted: no carrier, and that is the point
        }
        String carrier = text.substring(at + 1);
        return carrier.isBlank() ? "carrier?" : carrier;
    }

    /** The thread's identity with any carrier suffix removed — who it is, not where it is. */
    public static String labelOf(Thread thread) {
        if (!thread.isVirtual()) {
            return thread.getName();
        }
        String text = thread.toString();
        int at = text.lastIndexOf('@');
        String base = at < 0 ? text : text.substring(0, at);
        int slash = base.lastIndexOf('/');
        return slash < 0 ? base : base.substring(0, slash);
    }

    private static MountEvent mark(int request, String label, MountEvent.Kind kind,
                                   String service) {
        long nanos = System.nanoTime();
        return new MountEvent(nanos, request, label, carrierOf(Thread.currentThread()),
                kind, service);
    }

    /**
     * Named {@code pool-thread-N} because that is what the room has spent twenty years
     * reading in stack traces, and because it must not read like a carrier: the scheduler's
     * threads print as {@code worker-N} on the same board, and the entire subject of this
     * demo is that those two things are not the same.
     */
    private static ThreadFactory platformFactory() {
        return Thread.ofPlatform().name("pool-thread-", 0).daemon(true).factory();
    }

    private void deliver(int runGeneration, Runnable action) {
        Platform.runLater(() -> {
            if (runGeneration == generation) {
                action.run();
            }
        });
    }

    /*
     * ------------------------------------------------------------------ displayed source
     *
     * What "Show the code" puts on screen: an extract of runOne above, kept adjacent to it
     * for the reason OrderServer gives about its own handler sources — edit one, edit the
     * other. The audience should be able to see that the board is built out of eight
     * one-line marks and nothing cleverer, and that the pinned run adds exactly one line.
     */

    public static String sourceFor(Shape shape) {
        return shape.guarded() ? GUARDED_SOURCE : RECORDED_SOURCE;
    }

    public static final String RECORDED_SOURCE = """
            mark(START);                             // which carrier am I on?

            mark(CALL,   "findUser");
            User user = service.findUser(id);        // blocks
            mark(RETURN, "findUser");                // ...and which carrier now?

            mark(CALL,   "findOrder");
            Order order = service.findOrder(user);   // blocks
            mark(RETURN, "findOrder");

            mark(CALL,   "chargeCard");
            service.chargeCard(user, order);         // blocks
            mark(RETURN, "chargeCard");

            mark(END);

            // A mark is one line, and it is taken on the request's OWN thread:
            //
            //     new MountEvent(System.nanoTime(), request, label,
            //                    carrierOf(Thread.currentThread()), kind, service)
            //
            // carrierOf reads Thread.toString(), which is public information.
            // A MOUNTED virtual thread prints
            //     VirtualThread[#23,req-0]/runnable@ForkJoinPool-1-worker-1
            // and a PARKED one prints
            //     VirtualThread[#23,req-0]/timed_waiting
            // with no carrier at all.
            //
            // No agent, no JFR, no jdk.internal. The JDK just tells you where it put
            // your thread — and when nothing is running it, it says that too.
            """;

    public static final String GUARDED_SOURCE = """
            mark(START);

            synchronized (monitor) {                     // <-- THE ONLY LINE THAT CHANGED

                mark(CALL,   "findUser");
                User user = service.findUser(id);        // blocks
                mark(RETURN, "findUser");                // ...on the SAME carrier

                mark(CALL,   "findOrder");
                Order order = service.findOrder(user);   // blocks
                mark(RETURN, "findOrder");

                mark(CALL,   "chargeCard");
                service.chargeCard(user, order);         // blocks
                mark(RETURN, "chargeCard");
            }

            mark(END);

            // FIRST, the objection everyone in the room is about to raise.
            // This monitor belongs to THIS request and to nothing else:
            //
            //     Object[] monitors = new Object[requests];   // one each
            //
            // Not one request ever waits for another's lock. There is no contention
            // here to explain the stall with. What is left is the carrier.
            //
            // On JDK 21 a virtual thread inside a monitor CANNOT unmount. Instead of
            // letting go, it parks its CARRIER — and says so out loud:
            //
            //     VirtualThread[#23,req-0]/timed_waiting@ForkJoinPool-1-worker-6
            //                              ^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^
            //                              it IS waiting  and it STILL has a carrier
            //
            //     at java.base/java.lang.VirtualThread.parkOnCarrierThread(...)
            //
            // Both of those vanish the moment the monitor does. Swap it for a lock:
            //
            //     lock.lock();
            //     try { ...the same three calls... } finally { lock.unlock(); }
            //
            // ...and the board goes back to what the middle button draws. Rewriting
            // synchronized as ReentrantLock WAS the JDK 21 workaround.
            //
            // JDK 24's JEP 491 made synchronized do that by itself — same code as
            // above, no rewrite, no pin. This app runs on 21, which is why the pin is
            // still here to look at.
            """;
}
