package loomdemo.exec;

import loomdemo.Mode;

/**
 * The Java source shown in the editor for each mode. This is real, runnable code — it is
 * written to a temp file and executed by a child JVM exactly as it appears on screen.
 *
 * <p><b>Both modes run the same program.</b> There is one {@link #TEMPLATE}, and the two
 * constants differ by a single substitution: {@code ofPlatform} or {@code ofVirtual}. That
 * is the whole claim of the demo, so it is enforced by construction rather than by two
 * source constants that would drift apart the first time either was edited. Anything that
 * needs to differ between the runs — there is nothing, including the JVM flags — would
 * have to break that guarantee to exist.
 *
 * <p>Two lines are load-bearing for the UI: {@code "Died at thread #N of M"} and
 * {@code "All N threads alive"}. {@link ChildJvmRunner} watches the output stream for those
 * and the tab promotes them to the big punchline banner. Editing them live is fine; the
 * demo still runs, you just lose the banner.
 */
public final class DemoSources {

    private DemoSources() {
    }

    public static String forMode(Mode mode) {
        return mode == Mode.PAST ? PAST : FUTURE;
    }

    /**
     * The program, with {@code $FACTORY$} standing in for the one word that changes.
     *
     * <p>Substituted with {@code String.replace}, not {@code formatted} — the source is
     * full of {@code %,d} printf patterns that a format call would try to interpret.
     */
    private static final String TEMPLATE = """
            import java.time.Duration;

            /*
             * ONE PROGRAM, RUN TWICE. ONE WORD DIFFERENT.
             *
             * Ask for a million threads, each sleeping one hour, and count how many you
             * actually get. Everything here is identical in both runs except a single word
             * on the marked line below: ofPlatform, or ofVirtual.
             *
             * A platform thread is a real OS thread with a 1 MB stack — cheap to create,
             * ruinously expensive to have. A virtual thread is a few hundred bytes on the
             * heap, and a blocking sleep parks it instead of pinning the carrier it runs on.
             *
             * The sleep is an hour so that nothing ever finishes while we are still counting.
             * The only number here is how many threads are alive at once.
             *
             * Runs with: -Xmx2g -Xss1m — the same flags, both times.
             */
            public class Demo {

                static final int TASKS = 1_000_000;

                public static void main(String[] args) {

                    System.out.printf("Asking for %,d threads, each sleeping 1 hour...%n", TASKS);
                    System.out.println();

                    int started = 0;

                    try {
                        for (int i = 1; i <= TASKS; i++) {

                            // ↓   the only word that changes between the two runs   ↓
                            Thread.$FACTORY$().start(() -> {
                                try {
                                    Thread.sleep(Duration.ofHours(1));
                                } catch (InterruptedException e) {
                                }
                            });
                            started = i;

                            if (i % 1_000 == 0) {
                                System.out.printf("  alive: %,d%n", i);
                            }
                        }

                        System.out.println();
                        System.out.printf("All %,d threads alive.%n", TASKS);

                    } catch (Throwable failure) {
                        System.out.println();
                        System.out.printf("Died at thread #%,d of %,d%n", started + 1, TASKS);
                        System.out.println(failure);

                        // A thousand threads are still sleeping. Leave now, don't wait.
                        System.exit(0);
                    }

                    System.exit(0);
                }
            }
            """;

    /** The word the two runs disagree about. Nothing else in the program does. */
    private static final String PLACEHOLDER = "$FACTORY$";

    /** Platform threads, one real OS thread each, until the machine says no. */
    public static final String PAST = TEMPLATE.replace(PLACEHOLDER, "ofPlatform");

    /** The same program, one word changed. */
    public static final String FUTURE = TEMPLATE.replace(PLACEHOLDER, "ofVirtual");
}
