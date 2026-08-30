package loomdemo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.DemoTab;
import loomdemo.Era;
import loomdemo.exec.ChildJvmRunner;
import loomdemo.exec.SourceCompiler;
import loomdemo.exec.WorkaroundSnippet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tab 4: what giving up thread-per-request costs you.
 *
 * <p>The tab before this one argues that thread-per-request was the good design, and its last
 * snippet makes the case with a failure: one thread ran the whole request, so the stack trace
 * <em>is</em> the whole request. This tab writes that same request the other way — the same
 * three calls composed out of {@code CompletableFuture} stages — breaks it at the same
 * gateway, and puts the two traces side by side.
 *
 * <p>The Performance Comparison tab makes a version of this argument too, and keeps it: there
 * it is the counterweight to a throughput number that async wins, and it needs the load test
 * next to it to mean anything. Here it stands on its own, three tabs earlier, where the room
 * has just been told what thread-per-request was worth and has not yet been told what replaced
 * it. Two exhibits, one point, at the two moments the point is worth making.
 *
 * <p>Structurally this is the two-view version of {@link PerfCompareTab}: an explicit
 * {@code View} enum rather than a toggle guessing at itself, work in a child JVM with the
 * callbacks marshalled back to FX by {@link ChildJvmRunner}, and inline warnings instead of
 * dialogs. Unlike {@link ThreadPerRequestTab} the source is read-only, which removes the
 * dirty badge, the discard-my-edits confirm bar and the debounced recompile along with it.
 */
public final class WorkaroundTab implements DemoTab {

    /**
     * The source file every frame the snippet compiled carries, and so what marks a frame as
     * the reader's own code here. A snippet is compiled into the default package — see
     * {@link WorkaroundSnippet} — so {@link TraceView}'s usual {@code loomdemo.} prefix would
     * match nothing and leave both panels uncoloured.
     */
    private static final String SNIPPET_FILE = "Demo.java";

    /** Frames on the entry class, which is where the snippet keeps its handler methods. */
    private static final String HANDLER_FRAME = "at Demo.";

    private static final String COUNT_TOOLTIP =
            "Frames naming the handler — Demo.serve, Demo.handle.\n"
                    + "They are what let you answer \"which request broke?\" from the trace "
                    + "alone.\n\n"
                    + "Unwrapping the CompletionException does not give them back. serveAsync "
                    + "had already returned by the time the gateway threw, so it is not on the "
                    + "stack at any depth — there is nothing to unwrap to.";

    /**
     * What each panel measures.
     *
     * <p>Not {@link TraceView#REQUEST_FRAMES}, which counts frames naming the HTTP server's
     * request path. Nothing here runs over HTTP, so that reading would print a truthful and
     * completely meaningless zero under <em>both</em> traces and quietly destroy the contrast.
     * The handler is what stands in for the request in a snippet, so the handler is what gets
     * counted — the same question, asked of a program instead of a server.
     */
    private static final TraceView.Metric HANDLER_FRAMES = trace -> {
        int frames = handlerFrameCount(trace);
        return new TraceView.Footer("Frames naming the handler:  " + frames, frames == 0);
    };

    private final ChildJvmRunner runner = new ChildJvmRunner();
    private final SourceCompiler compiler = new SourceCompiler();

    private final JavaCodeArea code = new JavaCodeArea();
    private final Label codeHeader = new Label();

    /*
     * PAST rather than PRESENT for the blocking panel, which is the opposite of the default
     * PerfCompareTab picks. Two reasons, and they agree: the snippet hands the request a
     * platform thread, which is literally the past; and virtual threads have not been
     * introduced yet at this point in the talk, so teal here would be promising an answer
     * three tabs before it arrives. What is on screen is the story so far — the design that
     * worked, and the thing people reached for when it stopped scaling.
     */
    private final TraceView blockingTrace = new TraceView(
            Era.PAST, "blocking", HANDLER_FRAMES, COUNT_TOOLTIP, SNIPPET_FILE);
    private final TraceView asyncTrace = new TraceView(
            Era.WORKAROUND, "async", HANDLER_FRAMES, COUNT_TOOLTIP, SNIPPET_FILE);

    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset");
    private final ToggleButton viewButton = new ToggleButton();

    private final Label caption = new Label(WorkaroundSnippet.CAPTION);
    private final Label status = new Label();
    private final Label warning = new Label();
    private final Label breakLine = new Label();

    /** Everything the child printed this run, split into panels once it has exited. */
    private final List<String> captured = new ArrayList<>();

    private final VBox codeBox;
    private final VBox traceBox;
    private final VBox node;

    /** The two things that can occupy the space below the caption. */
    private enum View { CODE, TRACES }

    /** Guards against {@code setSelected} re-entering the toggle's own listener. */
    private boolean suppressViewSync;

    public WorkaroundTab() {
        caption.getStyleClass().add("snippet-caption");
        caption.setMaxWidth(Double.MAX_VALUE);
        caption.setWrapText(true);

        warning.getStyleClass().add("warning-text");
        warning.setWrapText(true);
        hide(warning);

        codeBox = buildCodeView();
        codeBox.getStyleClass().add("code-view");

        traceBox = buildTraceView();
        traceBox.getStyleClass().add("break-view");
        hide(traceBox);

        VBox content = new VBox(10, buildControls(), caption, warning, codeBox, traceBox);
        content.setPadding(new Insets(12));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPannable(false);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        node = new VBox(scroller);

        // Compile now, in the background, so Run is never the thing waiting on javac. The
        // source cannot change, so unlike ThreadPerRequestTab this happens exactly once.
        compiler.prepare(WorkaroundSnippet.SOURCE);

        showView(View.CODE);
        setRunning(false);
    }

    private static void hide(javafx.scene.Node target) {
        target.setVisible(false);
        target.setManaged(false);
    }

    private static void show(javafx.scene.Node target, boolean visible) {
        target.setVisible(visible);
        target.setManaged(visible);
    }

    /**
     * Exactly one of the code and the traces is on screen at a time.
     *
     * <p>The tab opens on the code, because the code is what the presenter talks over before
     * pressing anything, and Run moves it here — the traces are the answer to a question the
     * listing has just asked.
     */
    private void showView(View view) {
        show(codeBox, view == View.CODE);
        show(traceBox, view == View.TRACES);

        suppressViewSync = true;
        viewButton.setSelected(view == View.TRACES);
        viewButton.setText(view == View.TRACES ? "Show the code" : "Show the traces");
        suppressViewSync = false;
    }

    // ------------------------------------------------------------------ controls

    private FlowPane buildControls() {
        runButton.getStyleClass().addAll("run-button", Era.WORKAROUND.styleClass());
        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip(
                "Run this code in a separate JVM  (⌘R)\nIt fails on purpose, both ways."));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Kill the child JVM  (⌘.)"));

        resetButton.getStyleClass().add("secondary-button");
        resetButton.setOnAction(e -> clearOutput());
        resetButton.setTooltip(new Tooltip("Empty both panels and go back to the code  (⌘K)"));

        viewButton.getStyleClass().add("secondary-button");
        viewButton.setTooltip(new Tooltip(
                "Swap between the listing and the two traces it produced."));
        viewButton.selectedProperty().addListener((o, was, is) -> {
            if (!suppressViewSync) {
                showView(is ? View.TRACES : View.CODE);
            }
        });

        status.getStyleClass().add("elapsed-timer");

        for (Region control : new Region[]{runButton, stopButton, resetButton, viewButton}) {
            // Never shrink a control below its own label; the FlowPane wraps instead.
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        FlowPane row = new FlowPane(10, 8, runButton, stopButton, resetButton, viewButton,
                status);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------ views

    private VBox buildCodeView() {
        codeHeader.getStyleClass().add("handler-code-header");
        codeHeader.setText("Demo.java  ·  the same request written twice  ·  read-only");

        code.setEditable(false);
        code.loadSource(WorkaroundSnippet.SOURCE);

        Region codeNode = code.getNode();
        codeNode.getStyleClass().add("editor-frame");
        codeNode.setMinHeight(300);
        VBox.setVgrow(codeNode, Priority.ALWAYS);

        VBox box = new VBox(6, codeHeader, codeNode);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildTraceView() {
        breakLine.getStyleClass().add("break-line");
        breakLine.setMaxWidth(Double.MAX_VALUE);
        breakLine.setAlignment(Pos.CENTER);
        breakLine.setWrapText(true);
        hide(breakLine);

        HBox row = new HBox(12, blockingTrace.getNode(), asyncTrace.getNode());
        row.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(row, Priority.ALWAYS);

        VBox box = new VBox(10, row, breakLine);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** @see #HANDLER_FRAMES */
    private static int handlerFrameCount(String trace) {
        return (int) trace.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(HANDLER_FRAME))
                .count();
    }

    // ----------------------------------------------------------------- execution

    @Override
    public void runDemo() {
        if (runner.isRunning()) {
            return;
        }
        captured.clear();
        blockingTrace.clear();
        asyncTrace.clear();
        hide(breakLine);
        hideWarning();
        showView(View.TRACES);

        setRunning(true);
        status.setText("breaking it…");

        String source = WorkaroundSnippet.SOURCE;
        Optional<SourceCompiler.Compiled> compiled = compiler.ready(source);

        ChildJvmRunner.Listener listener = new ChildJvmRunner.Listener() {
            @Override
            public void onLines(List<String> lines) {
                // Collected, not rendered. A trace only means anything whole, and this run is
                // over in well under a second — there is nothing here worth streaming.
                captured.addAll(lines);
            }

            @Override
            public void onTick(long elapsedMillis) {
                // Nothing to count down. The run is shorter than a timer would be readable for.
            }

            @Override
            public void onExit(int exitCode, boolean stoppedByUser, long elapsedMillis) {
                setRunning(false);
                status.setText("");
                if (stoppedByUser) {
                    showWarning("Stopped before the traces came back. Press Run again.");
                    return;
                }
                applyTraces(exitCode);
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                status.setText("");
                showWarning("Could not run it: " + message);
            }
        };

        if (compiled.isPresent()) {
            runner.startCompiled(compiled.get().classDir(), compiled.get().mainClass(),
                    List.of(), listener);
        } else {
            // Not compiled yet. The source launcher is slower but it prints the real javac
            // error, which the warning line below puts on screen rather than swallowing.
            runner.start(source, List.of(), listener);
        }
    }

    /**
     * Split the child's output on the two markers and fill a panel from each half.
     *
     * <p>Anything that is not a trace — a {@code javac} error from the source-launcher
     * fallback, a JVM that died before {@code main} — has neither marker in it, and lands in
     * the warning line intact. There is no console on this tab, so this is the only thing
     * standing between the presenter and an empty panel with no explanation.
     */
    private void applyTraces(int exitCode) {
        int blockingAt = captured.indexOf(WorkaroundSnippet.BLOCKING_MARKER);
        int asyncAt = captured.indexOf(WorkaroundSnippet.ASYNC_MARKER);

        if (blockingAt < 0 || asyncAt <= blockingAt) {
            String output = String.join("\n", captured).strip();
            showWarning(output.isEmpty()
                    ? "The child JVM printed nothing and exited with code " + exitCode + "."
                    : output);
            blockingTrace.setError("No trace came back — see the message above.");
            asyncTrace.setError("No trace came back — see the message above.");
            return;
        }

        String blocking = join(captured.subList(blockingAt + 1, asyncAt));
        String async = join(captured.subList(asyncAt + 1, captured.size()));
        applyTrace(blockingTrace, blocking);
        applyTrace(asyncTrace, async);

        boolean both = !blocking.isEmpty() && !async.isEmpty();
        show(breakLine, both);
        if (both) {
            breakLine.setText(
                    "Same failure, same three calls. Only one trace names the request.");
        }
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines).strip();
    }

    /** A half that came back empty is an error, not a trace with zero handler frames in it. */
    private static void applyTrace(TraceView view, String trace) {
        if (trace.isEmpty()) {
            view.setError("No trace came back.");
        } else {
            view.setTrace(trace);
        }
    }

    /** Inline, never a popup — a modal dialog mid-talk is worse than the mistake it warns of. */
    private void showWarning(String message) {
        warning.setText(message);
        show(warning, true);
    }

    private void hideWarning() {
        hide(warning);
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        resetButton.setDisable(running);
        // The view toggle stays live: looking at the code while it runs costs nothing.
    }

    @Override
    public void stopDemo() {
        runner.stop();
    }

    /** ⌘K here means "put it back the way it opened" — both panels empty, code on screen. */
    @Override
    public void clearOutput() {
        if (runner.isRunning()) {
            return;
        }
        captured.clear();
        blockingTrace.clear();
        asyncTrace.clear();
        hide(breakLine);
        hideWarning();
        showView(View.CODE);
        status.setText("");
    }

    @Override
    public void shutdown() {
        runner.stop();
        compiler.shutdown();
    }

    public VBox getNode() {
        return node;
    }
}
