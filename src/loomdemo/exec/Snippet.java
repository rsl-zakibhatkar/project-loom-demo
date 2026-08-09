package loomdemo.exec;

/**
 * The three teaching snippets behind the Threads 101 tab.
 *
 * <p>Like {@link DemoSources}, this is real code: it is compiled and executed by a child
 * JVM exactly as it appears in the editor, so the presenter can edit it live and the
 * audience is never shown something that only pretends to run.
 *
 * <p>One convention is load-bearing for the console colouring: output lines of the form
 * {@code <thread-name> -> <value>} are recognised by the tab and tinted per thread name.
 * Change the arrow and you keep the demo, you just lose the colours.
 */
public enum Snippet {

    TWO_COOKS(
            "Two Cooks",
            "Who goes first? Run it again and find out.",
            """
            /*
             * TWO COOKS — the smallest interesting concurrent program.
             *
             * Two threads, one kitchen. Both run the same code at the same time, and
             * nobody is in charge of the order. Run this more than once: the lines
             * interleave differently each time, and nothing in the code changed.
             */
            public class Demo {

                public static void main(String[] args) throws InterruptedException {

                    Runnable cooking = () -> {
                        for (int i = 0; i < 5; i++) {
                            System.out.println(Thread.currentThread().getName() + " -> " + i);
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    };

                    Thread cook1 = new Thread(cooking, "cook-1");
                    Thread cook2 = new Thread(cooking, "cook-2");

                    cook1.start();
                    cook2.start();

                    cook1.join();
                    cook2.join();

                    System.out.println("kitchen closed");
                }
            }
            """),

    RUN_VS_START(
            "run() vs start()",
            "Same code — but who runs it?",
            """
            /*
             * RUN() vs START() — the classic mistake.
             *
             * Identical to Two Cooks except for two characters: run() instead of
             * start(). The Thread objects still exist. They still have names. But
             * run() is just a method call, so main executes both loops itself, in
             * order, and no second thread is ever born.
             *
             * Watch the name printed on every line.
             */
            public class Demo {

                public static void main(String[] args) throws InterruptedException {

                    Runnable cooking = () -> {
                        for (int i = 0; i < 5; i++) {
                            System.out.println(Thread.currentThread().getName() + " -> " + i);
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    };

                    Thread cook1 = new Thread(cooking, "cook-1");
                    Thread cook2 = new Thread(cooking, "cook-2");

                    cook1.run();
                    cook2.run();

                    System.out.println("kitchen closed");
                }
            }
            """),

    WHO_IS_IN_MY_JVM(
            "Who's in my JVM?",
            "Even Hello World isn't single-threaded.",
            """
            import java.util.Comparator;
            import java.util.Set;

            /*
             * WHO IS IN MY JVM? — you never had just one thread.
             *
             * This program starts no threads of its own. It only asks who is already
             * here: the garbage collector's helpers, the reference handler, the
             * signal dispatcher, and main.
             */
            public class Demo {

                public static void main(String[] args) {

                    // One snapshot. Asking twice can give two different answers.
                    Set<Thread> live = Thread.getAllStackTraces().keySet();

                    System.out.println("Threads alive in this JVM right now:");
                    System.out.println();

                    live.stream()
                            .sorted(Comparator.comparing(Thread::getName))
                            .forEach(t -> System.out.printf("  %-26s %s%n",
                                    t.getName(), t.isDaemon() ? "(daemon)" : ""));

                    System.out.println();
                    System.out.printf("Total: %d threads%n", live.size());
                }
            }
            """);

    private final String label;
    private final String caption;
    private final String source;

    Snippet(String label, String caption, String source) {
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
