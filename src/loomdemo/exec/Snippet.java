package loomdemo.exec;

/**
 * The six teaching snippets behind the Threads 101 tab.
 *
 * <p>Like {@link DemoSources}, this is real code: it is compiled and executed by a child
 * JVM exactly as it appears in the editor, so the presenter can edit it live and the
 * audience is never shown something that only pretends to run.
 *
 * <p>One convention is load-bearing for the console colouring: a line that opens with a
 * thread name followed by {@code ->}, {@code receives} or {@code serves} is recognised by
 * the tab and tinted per thread name. Reach for a third verb and you keep the demo, you
 * just lose the colours until {@code Threads101Tab.SPEAKER} has been taught it.
 */
public enum Snippet {

    TWO_COOKS(
            "Two Cooks",
            "Who goes first? Run it again and find out.",
            """
            import java.util.concurrent.ThreadLocalRandom;

            /*
             * TWO COOKS — the smallest interesting concurrent program.
             *
             * Two threads, one kitchen. Both run the same code at the same time, and
             * nobody is in charge of the order. Run this more than once: the lines
             * interleave differently each time, and nothing in the code changed.
             */
            public class Demo {

                public static void main(String[] args) throws InterruptedException {

                    // One recipe. Not one each — the same object, handed to both cooks.
                    Runnable service = () -> {

                        // Each cook asks who they are. Same line, two different answers.
                        String me = Thread.currentThread().getName();

                        for (int orderNo = 1; orderNo <= 3; orderNo++) {
                            System.out.println(me + " receives Order #" + orderNo);
                            try {
                                // the oven — this is where the cook waits
                                Thread.sleep(ThreadLocalRandom.current().nextInt(50, 250));
                            } catch (InterruptedException e) { return; }
                            System.out.println(me + " serves   Order #" + orderNo);
                        }
                    };

                    Thread cook1 = new Thread(service, "Cook#1");
                    Thread cook2 = new Thread(service, "Cook#2");

                    cook1.start();
                    cook2.start();

                    cook1.join();
                    cook2.join();

                    System.out.println("kitchen closed");
                }
            }
            """),

    TWO_COOKS_SHARED(
            "Two Cooks, Shared",
            "One pad. Now no two cooks hold the same ticket.",
            """
            import java.util.concurrent.ConcurrentLinkedQueue;
            import java.util.concurrent.ThreadLocalRandom;
            import java.util.concurrent.atomic.AtomicInteger;

            /*
             * TWO COOKS, ONE ORDER PAD — where the number comes from.
             *
             * Look at Two Cooks again before this one. Both cooks count 1, 2, 3, so
             * the kitchen ends up holding two Order #1s. That is not a race: nothing
             * is corrupted, and both threads did exactly what the code says. The code
             * says the wrong thing. orderNo is a local, and a local cannot identify
             * an order that belongs to the whole kitchen.
             *
             * Here the number is torn off one shared pad instead. Each cook still
             * handles three orders — that tally is its own, on its own stack — but the
             * ticket it gets belongs to the kitchen, and no two cooks hold the same one.
             */
            public class Demo {

                // Three orders each. Change it — both summary lines still tell the truth.
                static final int ORDERS = 3;

                public static void main(String[] args) throws InterruptedException {

                    // ONE pad. Not one each — created here, captured by the lambda
                    // below, so both cooks tear their tickets off the same object.
                    OrderPad pad = new OrderPad();

                    // One recipe. Not one each — the same object, handed to both cooks.
                    Runnable service = () -> {

                        // Each cook asks who they are. Same line, two different answers.
                        String me = Thread.currentThread().getName();

                        // mine is this cook's own tally, on this cook's own stack. The
                        // other cook has one too, and it counts to three as well.
                        for (int mine = 1; mine <= ORDERS; mine++) {
                            int orderNo = pad.take(me);
                            System.out.println(me + " receives Order #" + orderNo
                                    + "  ->  my " + mine + " of " + ORDERS);
                            try {
                                // the oven — the cook waits
                                Thread.sleep(ThreadLocalRandom.current().nextInt(50, 250));
                            } catch (InterruptedException e) { return; }
                            System.out.println(me + " serves   Order #" + orderNo);
                        }
                    };

                    Thread cook1 = new Thread(service, "Cook#1");
                    Thread cook2 = new Thread(service, "Cook#2");

                    cook1.start();
                    cook2.start();

                    // Nothing below reads the pad until both cooks have stopped. Move
                    // the summary above these two lines and it counts a kitchen that is
                    // still working.
                    cook1.join();
                    cook2.join();

                    System.out.println();
                    System.out.println("Each cook handled " + ORDERS
                            + " orders  (its own count, on its own stack)");
                    System.out.println("The kitchen took " + pad.taken()
                            + " orders   (one pad, shared on the heap)");
                }
            }

            class OrderPad {

                // thread-safe on purpose — why is a later slide
                private final AtomicInteger nextOrder = new AtomicInteger();
                private final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<>();

                /** Tears off the next ticket. Both cooks call this; neither gets the other's. */
                int take(String cook) {
                    int orderNo = nextOrder.incrementAndGet();
                    log.add(cook + " took #" + orderNo);
                    return orderNo;
                }

                /** How many tickets went out. Only exact once both cooks have stopped. */
                int taken() {
                    return log.size();
                }
            }
            """),

    RUN_VS_START(
            "run() vs start()",
            "Same code — but who runs it?",
            """
            import java.util.concurrent.ThreadLocalRandom;

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

                    // One recipe. Not one each — the same object, handed to both cooks.
                    Runnable service = () -> {

                        // Each cook asks who they are. Same line, two different answers.
                        String me = Thread.currentThread().getName();

                        for (int orderNo = 1; orderNo <= 3; orderNo++) {
                            System.out.println(me + " receives Order #" + orderNo);
                            try {
                                // the oven — this is where the cook waits
                                Thread.sleep(ThreadLocalRandom.current().nextInt(50, 250));
                            } catch (InterruptedException e) { return; }
                            System.out.println(me + " serves   Order #" + orderNo);
                        }
                    };

                    Thread cook1 = new Thread(service, "Cook#1");
                    Thread cook2 = new Thread(service, "Cook#2");

                    cook1.run();
                    cook2.run();

                    System.out.println("kitchen closed");
                }
            }
            """),

    ONE_OVEN(
            "One Oven",
            "Two cooks, one oven object, no rules.",
            """
            /*
             * ONE OVEN — two threads, one object.
             *
             * Each cook is a thread with its own stack, but there is only one Oven and
             * both of them are holding a reference to the same one. Watch the first two
             * lines: two different threads, printing the same object.
             *
             * Then watch the oven. Each cook puts a dish in and comes back for it — and
             * what they get back is whatever is in there by the time they return.
             */
            public class Demo {

                public static void main(String[] args) throws InterruptedException {

                    // One oven for the whole kitchen. Both cooks are handed the same one,
                    // not a copy of it.
                    Oven oven = new Oven();

                    // Two cooks, each with a dish of their own and a shift to work.
                    Thread cook1 = new Thread(() -> cook(oven, "lasagna"), "Cook#1");
                    Thread cook2 = new Thread(() -> cook(oven, "risotto"), "Cook#2");

                    // Both start at once. Nobody is holding a rota.
                    cook1.start();
                    cook2.start();

                    // The kitchen does not close until both cooks are done.
                    cook1.join();
                    cook2.join();

                    System.out.println("kitchen closed");
                }

                static void cook(Oven oven, String dish) {
                    String me = Thread.currentThread().getName();

                    // Two threads, two stacks, one address: the same oven, printed twice.
                    System.out.println(me + " -> shares " + oven);

                    // Three dishes each, one after the other.
                    for (int i = 0; i < 3; i++) {
                        oven.bake(dish);
                    }
                }
            }

            class Oven {

                // What is in the oven right now. One field, one oven, two cooks.
                private String inside;

                void bake(String dish) {
                    // 'dish' is this cook's own: a slot on their own stack, that nobody
                    // else can reach. 'inside' above belongs to the oven, and there is
                    // only ever one of it.
                    String me = Thread.currentThread().getName();

                    // Dish goes in. No checking, no deciding - this is the whole step.
                    System.out.println(me + " -> puts " + dish + " in");
                    inside = dish;

                    try {
                        // Timer set, and off to prep something else. The oven stands
                        // unattended for the whole bake, and the door has no lock.
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }

                    // Back for the dish. Whatever is in there now is what comes out —
                    // this cook has no way to know it is still their own.
                    String out = inside;
                    inside = null;
                    System.out.println(me + " -> wanted " + dish + ", got " + out);
                }
            }
            """),

    ONE_OVEN_LOCKED(
            "One Oven, Locked",
            "The same program, one word longer.",
            """
            /*
             * ONE OVEN, LOCKED — the same program, one word longer.
             *
             * bake() is now synchronized. The lock is the Oven itself: the one object
             * both cooks share is the one thing they now have to take turns on.
             *
             * Nothing else changed. Every "puts X in" is now followed by that same cook
             * getting their own dish back — and the two cooks never overlap.
             */
            public class Demo {

                public static void main(String[] args) throws InterruptedException {

                    // One oven for the whole kitchen. Both cooks are handed the same one,
                    // not a copy of it.
                    Oven oven = new Oven();

                    // Two cooks, each with a dish of their own and a shift to work.
                    Thread cook1 = new Thread(() -> cook(oven, "lasagna"), "Cook#1");
                    Thread cook2 = new Thread(() -> cook(oven, "risotto"), "Cook#2");

                    // Both start at once. Nobody is holding a rota.
                    cook1.start();
                    cook2.start();

                    // The kitchen does not close until both cooks are done.
                    cook1.join();
                    cook2.join();

                    System.out.println("kitchen closed");
                }

                static void cook(Oven oven, String dish) {
                    String me = Thread.currentThread().getName();

                    // Two threads, two stacks, one address: the same oven, printed twice.
                    System.out.println(me + " -> shares " + oven);

                    // Three dishes each, one after the other.
                    for (int i = 0; i < 3; i++) {
                        oven.bake(dish);
                    }
                }
            }

            class Oven {

                // What is in the oven right now. One field, one oven, two cooks.
                private String inside;

                // The door has a lock now, and the key is the oven itself. A cook who
                // arrives mid-bake waits at the door until the oven is free.
                synchronized void bake(String dish) {
                    // 'dish' is this cook's own: a slot on their own stack, that nobody
                    // else can reach. 'inside' above belongs to the oven, and there is
                    // only ever one of it.
                    String me = Thread.currentThread().getName();

                    // Dish goes in. No checking, no deciding - this is the whole step.
                    System.out.println(me + " -> puts " + dish + " in");
                    inside = dish;

                    try {
                        // Timer set — and this cook keeps the key while they wait.
                        // Holding the lock through the bake is what reserves the oven.
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }

                    // Back for the dish, and it can only be theirs: nobody could reach
                    // the oven while the timer was running.
                    String out = inside;
                    inside = null;
                    System.out.println(me + " -> wanted " + dish + ", got " + out);
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
