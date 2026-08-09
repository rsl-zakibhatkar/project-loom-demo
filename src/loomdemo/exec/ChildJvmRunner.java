package loomdemo.exec;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the editor's contents in a <em>separate</em> JVM and streams its output back.
 *
 * <p>This indirection is the whole reason the Thread Bomb demo is safe to perform live.
 * The past-mode program deliberately exhausts the OS thread limit; running it in this
 * process would take the window down with it. Instead we write the source to a temp file,
 * launch {@code java Demo.java} (single-file source execution) as a child process, and
 * treat its stdout as a stream of text. The child can die however it likes.
 *
 * <p>The child JVM is {@code System.getProperty("java.home")} — i.e. the runtime bundled
 * inside the .app. Nothing needs to be installed on the presenting machine. That runtime
 * must contain {@code jdk.compiler}; see the jlink module list in the Makefile.
 */
public final class ChildJvmRunner {

    /** All callbacks are delivered on the JavaFX application thread. */
    public interface Listener {
        void onLines(List<String> lines);

        void onTick(long elapsedMillis);

        void onExit(int exitCode, boolean stoppedByUser, long elapsedMillis);

        void onError(String message);
    }

    /** Roughly 30 Hz: fast enough to look live, slow enough not to flood the FX thread. */
    private static final Duration DRAIN_INTERVAL = Duration.millis(33);

    private static final Pattern CLASS_NAME =
            Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");

    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();

    private volatile Process process;
    private volatile boolean stoppedByUser;
    private Timeline drainTimeline;
    private Path workDir;
    private long startNanos;

    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /**
     * Compile-and-run {@code source} in a child JVM.
     *
     * @param jvmArgs flags placed before the source file, e.g. {@code -Xmx512m -Xss1m}
     */
    public void start(String source, List<String> jvmArgs, Listener listener) {
        if (isRunning()) {
            listener.onError("A run is already in progress.");
            return;
        }

        pending.clear();
        stoppedByUser = false;
        startNanos = System.nanoTime();

        Path sourceFile;
        try {
            workDir = Files.createTempDirectory("loom-demo-");
            workDir.toFile().deleteOnExit();
            // The source launcher requires the file name to match the public class, and
            // the presenter is allowed to rename that class mid-talk.
            sourceFile = workDir.resolve(detectClassName(source) + ".java");
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
            sourceFile.toFile().deleteOnExit();
        } catch (IOException e) {
            listener.onError("Could not write the temp source file: " + e);
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.addAll(jvmArgs);
        command.add(sourceFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        // stderr is merged so stack traces land inline, in the order they happened.
        builder.redirectErrorStream(true);
        // A stray JAVA_TOOL_OPTIONS on the presenting machine would silently change the
        // flags the audience just watched us set. Start from a known state.
        Map<String, String> env = builder.environment();
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        env.remove("JDK_JAVA_OPTIONS");

        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            listener.onError("Could not launch the child JVM: " + e);
            return;
        }
        process = started;

        Thread gobbler = new Thread(() -> readOutput(started), "child-jvm-output");
        gobbler.setDaemon(true);
        gobbler.start();

        Thread waiter = new Thread(() -> {
            int exitCode;
            try {
                exitCode = started.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitCode = -1;
            }
            long elapsed = elapsedMillis();
            boolean userStopped = stoppedByUser;
            int code = exitCode;
            Platform.runLater(() -> finish(code, userStopped, elapsed, listener));
        }, "child-jvm-waiter");
        waiter.setDaemon(true);
        waiter.start();

        drainTimeline = new Timeline(new KeyFrame(DRAIN_INTERVAL, e -> {
            drainTo(listener);
            listener.onTick(elapsedMillis());
        }));
        drainTimeline.setCycleCount(Animation.INDEFINITE);
        drainTimeline.play();
    }

    private void readOutput(Process target) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                pending.add(line);
            }
        } catch (IOException e) {
            // Expected when the process is destroyed mid-read.
            pending.add("[output stream closed: " + e.getMessage() + "]");
        } catch (Throwable t) {
            pending.add("[output reader failed: " + t + "]");
        }
    }

    private void drainTo(Listener listener) {
        if (pending.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>();
        String line;
        // Bound the batch so one enormous burst can't stall a frame.
        while (batch.size() < 2000 && (line = pending.poll()) != null) {
            batch.add(line);
        }
        listener.onLines(batch);
    }

    private void finish(int exitCode, boolean userStopped, long elapsed, Listener listener) {
        if (drainTimeline != null) {
            drainTimeline.stop();
            drainTimeline = null;
        }
        drainTo(listener);   // whatever arrived after the last tick
        cleanUp();
        listener.onExit(exitCode, userStopped, elapsed);
    }

    /** Kill the child. Safe to call when nothing is running. */
    public void stop() {
        Process current = process;
        if (current == null) {
            return;
        }
        stoppedByUser = true;
        current.descendants().forEach(ProcessHandle::destroyForcibly);
        current.destroyForcibly();
        try {
            current.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanUp() {
        process = null;
        Path dir = workDir;
        workDir = null;
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // Temp files are marked deleteOnExit as a backstop.
        }
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** The {@code java} binary of the runtime this app is running on. */
    public static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String detectClassName(String source) {
        Matcher matcher = CLASS_NAME.matcher(source);
        return matcher.find() ? matcher.group(1) : "Demo";
    }

    /** {@code 12.3s} — the elapsed readout next to the Run button. */
    public static String formatElapsed(long millis) {
        return String.format(Locale.US, "%.1fs", millis / 1000.0);
    }
}
