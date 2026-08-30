package loomdemo.exec;

/**
 * The two teaching snippets behind the "A Million Lockers" tab (talk slide 40, Story 3).
 *
 * <p>Like {@link Snippet} and {@link ThreadPerRequestSnippet}, this is real code, compiled and
 * run by a child JVM exactly as it appears in the editor. Both programs demonstrate the slide's
 * "ThreadLocal memory leak" the honest way — by leaking, and measuring it:
 *
 * <ul>
 *   <li>{@link #THREAD_LOCAL} — a {@code ThreadLocal} value lives as long as its <em>thread</em>
 *       does, not as long as the request does. On a pool of reused worker threads that forgets
 *       {@code remove()}, the context is stranded on the thread after the request is gone. The
 *       program serves 10,000 requests on 200 threads and reports how much is still held once
 *       the server is idle.</li>
 *   <li>{@link #SCOPED_VALUE} — the same pool and requests, but the context is bound with
 *       {@code where(...).run(...)}: reclaimed the instant the block returns, so nothing is left
 *       on the thread. Idle, the pool holds nothing.</li>
 * </ul>
 *
 * <p><strong>{@code ScopedValue} is a preview API on the bundled JDK 21</strong> (final in
 * JDK 25), so the tab source-launches both snippets with {@code --enable-preview --source 21}.
 * The context here is a raw {@code byte[]} standing in for "user data + transactions"; the
 * point is the lifetime, not the shape, so it does not need the order-service records.
 *
 * <p>{@code public class Demo} is declared first for entry-class detection; only {@code main}
 * prints, so there is no per-thread colouring.
 */
public enum LockerSnippet {

    THREAD_LOCAL(
            "ThreadLocal",
            "Forget remove() on a pooled thread, and the context never leaves.",
            """
            import java.util.concurrent.CountDownLatch;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;

            /*
             * THE THREADLOCAL LEAK — a value outlives the request that set it.
             *
             * A ThreadLocal value lives as long as its THREAD does, not as long as the
             * request does. On a pool of reused worker threads that is effectively forever:
             * call set(), forget remove(), and the context sits in that thread's locker
             * until the next request on that thread overwrites it — and if the thread goes
             * idle, for good.
             *
             * Here 200 pooled threads serve 10,000 requests, each stashing a ~1 MB context
             * and never calling remove(). When it is over and the server is idle, look at
             * how much is STILL on the heap: 200 threads each clutching the last context
             * they touched, for requests that finished long ago.
             *
             * (A framework like Spring hid this by calling remove() for you in a finally.
             * Hand-rolled virtual-thread code has no such safety net — which is the point.)
             */
            public class Demo {

                static final int POOL = 200;          // reused worker threads: the old thread-per-request pool
                static final int REQUESTS = 10_000;   // requests that come and go
                static final int CONTEXT_KB = 1024;   // each request's context: ~1 MB of user data + transactions
                static final ThreadLocal<byte[]> CONTEXT = new ThreadLocal<>();

                public static void main(String[] args) throws Exception {
                    ExecutorService pool = Executors.newFixedThreadPool(POOL);
                    CountDownLatch done = new CountDownLatch(REQUESTS);

                    long before = usedHeap();
                    System.out.println("idle baseline: " + mb(before) + " MB");
                    System.out.println("serving " + fmt(REQUESTS) + " requests on " + POOL + " reused threads...");

                    for (int i = 0; i < REQUESTS; i++) {
                        pool.execute(() -> {
                            CONTEXT.set(new byte[CONTEXT_KB * 1024]);   // stash context in THIS thread's locker
                            handle();                                   // ...serve the request...
                            // no CONTEXT.remove(): the context stays behind on this pooled thread
                            done.countDown();
                        });
                    }
                    done.await();                                       // every request has come and gone

                    long leaked = usedHeap() - before;
                    System.out.println("server idle, " + fmt(REQUESTS) + " requests finished: "
                            + mb(leaked) + " MB STILL HELD");
                    System.out.println("  = " + POOL + " idle threads, each still clutching a stale context");
                    pool.shutdown();                                    // only now do the threads die and let go
                }

                static void handle() { /* pretend to do the request's work */ }

                static long usedHeap() {
                    System.gc();
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.gc();
                    Runtime r = Runtime.getRuntime();
                    return r.totalMemory() - r.freeMemory();
                }

                static long mb(long bytes) { return bytes / (1024 * 1024); }
                static String fmt(int n) { return String.format("%,d", n); }
            }
            """),

    SCOPED_VALUE(
            "ScopedValue",
            "Bound to the block — reclaimed the instant the request ends.",
            """
            import java.util.concurrent.CountDownLatch;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;

            /*
             * SCOPEDVALUE — the context cannot outlive the request (preview in 21, final in 25).
             *
             * Same 200 pooled threads, same 10,000 requests, same ~1 MB context — but the
             * value is bound with where(...).run(...) instead of stashed in a locker. It is
             * readable for exactly the duration of that block and reclaimed the instant the
             * block returns. There is no remove() to forget, and nothing is left on the
             * thread between requests. It is also immutable: set once, no surprise writes
             * downstream.
             *
             * When the server goes idle, the pool is holding nothing.
             */
            public class Demo {

                static final int POOL = 200;
                static final int REQUESTS = 10_000;
                static final int CONTEXT_KB = 1024;
                static final ScopedValue<byte[]> CONTEXT = ScopedValue.newInstance();

                public static void main(String[] args) throws Exception {
                    ExecutorService pool = Executors.newFixedThreadPool(POOL);
                    CountDownLatch done = new CountDownLatch(REQUESTS);

                    long before = usedHeap();
                    System.out.println("idle baseline: " + mb(before) + " MB");
                    System.out.println("serving " + fmt(REQUESTS) + " requests on " + POOL + " reused threads...");

                    for (int i = 0; i < REQUESTS; i++) {
                        pool.execute(() -> {
                            // context lives ONLY inside run(); gone the instant handle() returns
                            ScopedValue.where(CONTEXT, new byte[CONTEXT_KB * 1024]).run(Demo::handle);
                            done.countDown();   // nothing to remove, nothing left on the thread to leak
                        });
                    }
                    done.await();

                    long held = usedHeap() - before;
                    System.out.println("server idle, " + fmt(REQUESTS) + " requests finished: "
                            + mb(held) + " MB still held");
                    System.out.println("  = the pool threads are holding nothing");
                    pool.shutdown();
                }

                static void handle() { /* pretend to do the request's work */ }

                static long usedHeap() {
                    System.gc();
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.gc();
                    Runtime r = Runtime.getRuntime();
                    return r.totalMemory() - r.freeMemory();
                }

                static long mb(long bytes) { return bytes / (1024 * 1024); }
                static String fmt(int n) { return String.format("%,d", n); }
            }
            """);

    private final String label;
    private final String caption;
    private final String source;

    LockerSnippet(String label, String caption, String source) {
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
