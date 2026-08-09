package loomdemo.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.spi.ToolProvider;

/**
 * Compiles a snippet in the background so that pressing Run only has to start a JVM.
 *
 * <p>Single-file source execution ({@code java Demo.java}) recompiles on every launch,
 * which costs most of a second. That is invisible in the Thread Bomb demo — the program
 * runs for seconds afterwards — but Threads 101 is built around pressing Run repeatedly to
 * show that two threads interleave differently each time, and a pause between the click
 * and the first line of output breaks that.
 *
 * <p>So the tab calls {@link #prepare} whenever the source settles, and {@link #ready}
 * just before launching. A miss is never fatal: the caller falls back to the source
 * launcher, which is exactly what the app did before this class existed. That fallback is
 * also what surfaces compile errors — a snippet the presenter has broken mid-talk fails
 * here silently and then fails loudly, with the real {@code javac} message, in the console.
 *
 * <p>Compilation runs in-process via {@link ToolProvider}, so it needs {@code jdk.compiler}
 * in the runtime. The Thread Bomb demo already required that module for the source
 * launcher; {@code make verify} checks both.
 */
public final class SourceCompiler {

    /** Where a successful compile put its class files, and what to launch from there. */
    public record Compiled(Path classDir, String mainClass) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "snippet-compiler");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger generation = new AtomicInteger();

    private volatile Path cacheRoot;
    /** The exact text that produced {@link #current}. Identity is by content, not by time. */
    private volatile String compiledSource;
    private volatile Compiled current;
    /** The most recent request, so a queued job can notice it has been superseded. */
    private volatile String requested;

    /**
     * Compile {@code source} in the background if it is not already compiled. Cheap to
     * call often — this is wired to a debounced editor listener.
     */
    public void prepare(String source) {
        if (source.equals(compiledSource)) {
            return;
        }
        requested = source;
        try {
            worker.execute(() -> {
                // Typing produces a burst of these; only the last one is worth compiling.
                if (source.equals(requested)) {
                    compile(source);
                }
            });
        } catch (Throwable ignored) {
            // Shutting down, or the queue rejected us. The source launcher still works.
        }
    }

    /** The compiled form of exactly this source, if we have it. */
    public Optional<Compiled> ready(String source) {
        Compiled compiled = current;
        if (compiled != null && source.equals(compiledSource)) {
            return Optional.of(compiled);
        }
        return Optional.empty();
    }

    private void compile(String source) {
        try {
            ToolProvider javac = ToolProvider.findFirst("javac").orElse(null);
            if (javac == null) {
                invalidate();
                return;
            }

            Path root = cacheRoot();
            // A fresh directory per compile: the presenter may rename the class, and a
            // stale .class file from the previous name would otherwise sit there forever.
            Path classDir = root.resolve("gen-" + generation.incrementAndGet());
            Files.createDirectories(classDir);

            String className = ChildJvmRunner.detectClassName(source);
            Path sourceFile = classDir.resolve(className + ".java");
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            StringWriter out = new StringWriter();
            int result;
            try (PrintWriter writer = new PrintWriter(out)) {
                result = javac.run(writer, writer,
                        "-nowarn", "-proc:none", "-d", classDir.toString(),
                        sourceFile.toString());
            }

            if (result == 0) {
                compiledSource = source;
                current = new Compiled(classDir, className);
            } else {
                invalidate();
            }
        } catch (Throwable t) {
            // Precompilation is an optimisation. Failing it must never stop a demo.
            invalidate();
        }
    }

    private void invalidate() {
        current = null;
        compiledSource = null;
    }

    private Path cacheRoot() throws IOException {
        Path root = cacheRoot;
        if (root == null) {
            root = Files.createTempDirectory("loom-snippets-");
            root.toFile().deleteOnExit();
            cacheRoot = root;
        }
        return root;
    }

    /** Stop compiling and delete the class-file cache. */
    public void shutdown() {
        worker.shutdownNow();
        invalidate();
        Path root = cacheRoot;
        cacheRoot = null;
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // deleteOnExit is the backstop.
        }
    }
}
