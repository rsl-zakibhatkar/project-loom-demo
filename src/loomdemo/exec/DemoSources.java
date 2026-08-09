package loomdemo.exec;

import loomdemo.Mode;

/**
 * The Java source shown in the editor for each mode. This is real, runnable code — it is
 * written to a temp file and executed by a child JVM exactly as it appears on screen.
 *
 * <p>Two lines are load-bearing for the UI: {@code "Died at thread #N"} and
 * {@code "Completed 1,000,000 tasks in Xs"}. {@link ChildJvmRunner} watches the output
 * stream for those and the tab promotes them to the big punchline banner. Editing them
 * live is fine; the demo still runs, you just lose the banner.
 */
public final class DemoSources {

    private DemoSources() {
    }

    public static String forMode(Mode mode) {
        return mode == Mode.PAST ? PAST : FUTURE;
    }

    /** Platform threads, one OS thread each, until the machine says no. */
    public static final String PAST = """
            import java.time.Duration;

            /*
             * BEFORE LOOM — one platform thread per task.
             *
             * Every Thread here is a real OS thread with its own 1 MB stack. They are
             * cheap to create and ruinously expensive to have. We keep making them
             * until the operating system refuses.
             *
             * Runs with: -Xmx512m -Xss1m
             */
            public class Demo {

                public static void main(String[] args) {
                    System.out.println("PAST — platform threads");
                    System.out.println("Each thread sleeps for one hour. Spawning until the JVM dies...");
                    System.out.println();

                    int count = 0;
                    try {
                        while (true) {
                            count++;

                            Thread thread = new Thread(() -> {
                                try {
                                    Thread.sleep(Duration.ofHours(1));
                                } catch (InterruptedException e) {
                                    // demo over
                                }
                            });
                            thread.start();

                            if (count % 100 == 0) {
                                System.out.printf("  live threads: %,d%n", count);
                            }
                        }
                    } catch (Throwable failure) {
                        System.out.println();
                        System.out.printf("Died at thread #%,d%n", count);
                        System.out.println(failure);
                    }

                    // Thousands of threads are still sleeping. Leave now, don't wait for them.
                    System.exit(0);
                }
            }
            """;

    /** Virtual threads: the same shape of program, four orders of magnitude more of them. */
    public static final String FUTURE = """
            import java.time.Duration;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            import java.util.concurrent.atomic.AtomicInteger;

            /*
             * AFTER LOOM — one VIRTUAL thread per task.
             *
             * Same program, same blocking Thread.sleep(). But a virtual thread is a few
             * hundred bytes on the heap, not a 1 MB OS stack, and a blocking sleep parks
             * it instead of pinning a carrier thread. So we ask for a million.
             *
             * (An hour-long sleep would work here too — all million would simply sit
             *  there. We sleep one second so the demo ends while you are still on stage.)
             *
             * Runs with: -Xmx2g
             */
            public class Demo {

                static final int TASKS = 1_000_000;

                public static void main(String[] args) throws Exception {
                    System.out.println("PRESENT — virtual threads, since Java 21");
                    System.out.printf("Starting %,d virtual threads, each sleeping 1 second...%n", TASKS);
                    System.out.println();

                    AtomicInteger completed = new AtomicInteger();
                    long start = System.nanoTime();

                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int i = 1; i <= TASKS; i++) {
                            executor.submit(() -> {
                                Thread.sleep(Duration.ofSeconds(1));
                                int done = completed.incrementAndGet();
                                if (done % 100_000 == 0) {
                                    System.out.printf("  completed: %,d%n", done);
                                }
                                return null;
                            });

                            if (i % 200_000 == 0) {
                                System.out.printf("  started:   %,d%n", i);
                            }
                        }
                        System.out.printf("%nAll %,d virtual threads are alive. Waiting for them...%n", TASKS);
                    } // close() blocks until every task finishes

                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    System.out.println();
                    System.out.printf("Completed %,d tasks in %.2fs%n", TASKS, seconds);

                    System.exit(0);
                }
            }
            """;
}
