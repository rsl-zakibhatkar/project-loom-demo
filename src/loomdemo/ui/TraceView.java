package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import loomdemo.Era;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

import java.util.ArrayList;
import java.util.List;

/**
 * One era's stack trace from the endpoint that always fails.
 *
 * <p>Built on {@code StyleClassedTextArea} for the same reason {@link StreamConsole} is:
 * every line needs its own colour, and a plain {@code TextArea} has one text fill for its
 * whole contents. {@link JavaCodeArea} would be the wrong tool — its Java regex would
 * cheerfully highlight {@code Exception} and {@code Thread} in a stack trace as types.
 *
 * <p>The count underneath is <em>request-path</em> frames, not "frames from your code", and
 * that distinction is the whole reason this exhibit survives scrutiny. Both traces name
 * {@code callPaymentGateway} — the async one does it under {@code Caused by:}, which this
 * panel shows rather than hides, because "just call getCause()" is a fair objection. What
 * unwrapping cannot give back is the request: on the async side it is not on the stack at
 * any depth, and that is what the number counts.
 */
public final class TraceView {

    /** Frames from the app itself. */
    private static final String OWN_PACKAGE = "loomdemo.";

    /**
     * Frames that tie the failure to an HTTP request.
     *
     * <p>One substring covers both spellings the JDK emits — {@code sun.net.httpserver} is
     * itself a substring of {@code com.sun.net.httpserver}, and a real trace carries both.
     */
    private static final String REQUEST_PACKAGE = "sun.net.httpserver";

    /**
     * How many consecutive framework frames it takes before the middle is collapsed.
     *
     * <p>Deliberately high. Measured on this JDK, the async trace's longest framework run is
     * <strong>six</strong> — {@code encodeThrowable}, {@code completeThrowable},
     * {@code AsyncSupply.run}, {@code runWorker}, {@code Worker.run}, {@code Thread.run} —
     * and those six frames <em>are</em> the exhibit: they are the pool worker the handler
     * comment talks about. Collapsing them would hide the entire point. The threshold exists
     * for a pathologically deep trace on some other runtime, and should never fire here; the
     * harness asserts that it does not.
     */
    private static final int COLLAPSE_THRESHOLD = 8;

    /** Frames kept at the head and tail of a collapsed run, for context. */
    private static final int KEEP_HEAD = 2;
    private static final int KEEP_TAIL = 1;

    /** What a line is, which decides both its colour and whether it can be collapsed. */
    public enum Kind {
        /** The exception line, {@code Caused by:}, {@code Suppressed:}. */
        HEADER("trace-header"),
        /** A frame in this application. */
        MINE("trace-mine"),
        /** A frame in the HTTP server's request path. */
        REQUEST("trace-request"),
        /** Anything else — the JDK, the executor, the CompletableFuture machinery. */
        FRAMEWORK("trace-framework"),
        /** The marker standing in for a collapsed run. */
        ELISION("trace-elision");

        private final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    /** One rendered line of a trace. */
    public record Line(String text, Kind kind) {
    }

    private final String role;
    private final Label header = new Label();
    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final Label count = new Label();
    private final VBox node;

    private Era era;

    /**
     * @param era  which era's colours to wear; changeable, because the blocking panel
     *             follows the era picker between past and present
     * @param role the word after the era in the header — "blocking" or "async"
     */
    public TraceView(Era era, String role) {
        this.era = era;
        this.role = role;

        header.setMaxWidth(Double.MAX_VALUE);

        area.setEditable(false);
        area.setWrapText(false);
        area.setFocusTraversable(false);

        VirtualizedScrollPane<StyleClassedTextArea> scroller = new VirtualizedScrollPane<>(area);
        scroller.setMinHeight(220);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        count.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(count, new Tooltip(
                "Frames naming the HTTP server's request path — Filter.doFilter, "
                        + "Exchange.run.\nThey are what let you answer \"which request "
                        + "broke?\" from the trace alone."));

        VBox body = new VBox(6, scroller, count);
        body.getStyleClass().add("trace-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        node = new VBox(header, body);
        HBox.setHgrow(node, Priority.ALWAYS);
        VBox.setVgrow(node, Priority.ALWAYS);

        setEra(era);
        clear();
    }

    /**
     * Repaint in another era's colours. The blocking panel is whichever of past or present
     * is selected — they run the same method, so the trace is the same shape either way and
     * only the identity colour needs to move.
     *
     * <p>{@code setAll} rather than add/remove: these nodes carry exactly one era class and
     * one structural class each, so replacing the lot is both shorter and impossible to get
     * out of step.
     */
    public void setEra(Era era) {
        this.era = era;
        header.getStyleClass().setAll("panel-header", era.styleClass());
        area.getStyleClass().setAll("trace-console", era.styleClass());
        node.getStyleClass().setAll("trace-panel", era.styleClass());
        header.setText(era.shortLabel().toUpperCase(java.util.Locale.US) + "  ·  " + role);

        // The count keeps whatever state class it has; only its era changes.
        List<String> stateClasses = new ArrayList<>(count.getStyleClass());
        stateClasses.removeIf(c -> !c.equals("empty") && !c.equals("zero"));
        count.getStyleClass().setAll("trace-count", era.styleClass());
        count.getStyleClass().addAll(stateClasses);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Classify a raw trace and collapse any long run of framework frames.
     *
     * <p>Public and static so it can be exercised without a scene — this is the part with
     * the interesting edge cases, and it should not need a window to test.
     */
    public static List<Line> parse(String trace) {
        List<Line> classified = new ArrayList<>();
        for (String raw : trace.stripTrailing().lines().toList()) {
            classified.add(new Line(raw, kindOf(raw)));
        }
        return collapse(classified);
    }

    private static Kind kindOf(String raw) {
        String line = raw.strip();
        if (line.startsWith("...")) {
            // The JDK's own "... 14 more" elision. Framework noise by definition.
            return Kind.FRAMEWORK;
        }
        if (!line.startsWith("at ")) {
            return Kind.HEADER;
        }
        if (line.contains(OWN_PACKAGE)) {
            return Kind.MINE;
        }
        if (line.contains(REQUEST_PACKAGE)) {
            return Kind.REQUEST;
        }
        return Kind.FRAMEWORK;
    }

    /**
     * Replace the middle of any long run of framework frames with a single marker. Runs are
     * broken by anything that is not a framework frame, so a {@code Caused by:} section
     * keeps its own shape rather than being merged into the section above it.
     */
    private static List<Line> collapse(List<Line> lines) {
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).kind() != Kind.FRAMEWORK) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int end = i;
            while (end < lines.size() && lines.get(end).kind() == Kind.FRAMEWORK) {
                end++;
            }
            int run = end - i;
            if (run < COLLAPSE_THRESHOLD) {
                out.addAll(lines.subList(i, end));
            } else {
                out.addAll(lines.subList(i, i + KEEP_HEAD));
                out.add(new Line("        … " + (run - KEEP_HEAD - KEEP_TAIL)
                        + " more framework frames", Kind.ELISION));
                out.addAll(lines.subList(end - KEEP_TAIL, end));
            }
            i = end;
        }
        return out;
    }

    /**
     * Frames tying the failure to an HTTP request, counted over the whole trace including
     * the {@code Caused by:} section. Collapsing never removes one — a run has to be all
     * framework frames to be collapsed at all.
     */
    public static int requestFrameCount(String trace) {
        return (int) trace.lines().filter(line -> kindOf(line) == Kind.REQUEST).count();
    }

    // ------------------------------------------------------------------ rendering

    public void setTrace(String trace) {
        area.replaceText("");
        for (Line line : parse(trace)) {
            area.append(line.text() + "\n", line.kind().styleClass());
        }
        area.showParagraphAtTop(0);
        int frames = requestFrameCount(trace);
        count.setText("Frames tying this to a request:  " + frames);
        count.getStyleClass().remove("empty");
        if (frames == 0) {
            count.getStyleClass().add("zero");
        } else {
            count.getStyleClass().remove("zero");
        }
    }

    /** A fetch that did not get far enough to produce a trace. Stays inside the panel. */
    public void setError(String message) {
        area.replaceText("");
        area.append(message + "\n", Kind.HEADER.styleClass());
        count.setText("no trace");
        count.getStyleClass().remove("zero");
        count.getStyleClass().add("empty");
    }

    public void clear() {
        area.replaceText("");
        count.setText("—");
        count.getStyleClass().remove("zero");
        if (!count.getStyleClass().contains("empty")) {
            count.getStyleClass().add("empty");
        }
    }

    public Era era() {
        return era;
    }

    /** The trace currently on screen, for tests and for nothing else. */
    public String getText() {
        return area.getText();
    }

    public String getCountText() {
        return count.getText();
    }

    public VBox getNode() {
        return node;
    }
}
