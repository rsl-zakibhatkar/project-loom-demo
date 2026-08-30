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
 *
 * <p>That reading is the default rather than the only one — see {@link Metric}. A stack
 * captured mid-<em>wait</em> rather than mid-failure has a different interesting question,
 * and the Frame by Frame tab asks it of the same panel.
 */
public final class TraceView {

    /**
     * Frames from the app itself, and the default answer to "which frames are mine".
     *
     * <p>Only a default because not every trace this panel shows comes from this process. A
     * snippet run in a child JVM has no package to name — it is compiled into the default one
     * so the source launcher can find its main class — and its frames would all classify as
     * {@link Kind#FRAMEWORK}, leaving a panel with nothing coloured and no exhibit. Such a
     * caller passes its own marker instead; see the five-argument constructor.
     */
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

    /**
     * The line under a trace.
     *
     * @param emphasised whether this reading <em>is</em> the point being made, in which case
     *                   it is allowed to shout — a zero request count, or an OS thread that
     *                   had to be held. See {@code .trace-count.zero}.
     */
    public record Footer(String text, boolean emphasised) {
    }

    /** How a panel measures the trace it is showing. */
    @FunctionalInterface
    public interface Metric {
        Footer measure(String trace);
    }

    /**
     * The original reading, and still the default: frames naming the HTTP request path.
     *
     * <p>Pluggable because it is not the only interesting question to ask of a stack. The
     * Frame by Frame tab shows a stack captured mid-wait rather than mid-failure, where there
     * is no HTTP request to count and a hard-coded zero would say something untrue.
     */
    public static final Metric REQUEST_FRAMES = trace -> {
        int frames = requestFrameCount(trace);
        return new Footer("Frames tying this to a request:  " + frames, frames == 0);
    };

    private static final String REQUEST_FRAMES_TOOLTIP =
            "Frames naming the HTTP server's request path — Filter.doFilter, "
                    + "Exchange.run.\nThey are what let you answer \"which request "
                    + "broke?\" from the trace alone.";

    private String role;
    private final Metric metric;
    /** What counts as "my code" here: a package prefix, or a snippet's source file name. */
    private final String ownMarker;
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
        this(era, role, REQUEST_FRAMES, REQUEST_FRAMES_TOOLTIP);
    }

    /**
     * As above, with a reading of its own. Everything else about the panel is unchanged —
     * the classifier, the colours and the collapsing are the same questions whatever the
     * trace came from.
     */
    public TraceView(Era era, String role, Metric metric, String tooltip) {
        this(era, role, metric, tooltip, OWN_PACKAGE);
    }

    /**
     * As above, for a trace whose own frames are not this application's.
     *
     * @param ownMarker the substring that marks a frame as the reader's own code. Defaults to
     *                  {@link #OWN_PACKAGE}; a snippet compiled into the default package has
     *                  no package prefix to match and passes its source file name instead,
     *                  which every frame javac compiled carries.
     */
    public TraceView(Era era, String role, Metric metric, String tooltip, String ownMarker) {
        this.era = era;
        this.role = role;
        this.metric = metric;
        this.ownMarker = ownMarker;

        header.setMaxWidth(Double.MAX_VALUE);

        area.setEditable(false);
        area.setWrapText(false);
        area.setFocusTraversable(false);

        VirtualizedScrollPane<StyleClassedTextArea> scroller = new VirtualizedScrollPane<>(area);
        scroller.setMinHeight(220);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        count.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(count, new Tooltip(tooltip));

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
    /**
     * Change the word after the era in the header.
     *
     * <p>Mutable for the same reason {@link #setEra} is: one panel is reused for more than one
     * exhibit. The Frame by Frame tab's right-hand panel shows either a platform thread parked
     * in the kernel or a virtual thread pinned to its carrier, and those are two different
     * sentences about two different problems, not one sentence in two colours.
     */
    public void setRole(String role) {
        this.role = role;
        setEra(era);
    }

    public void setEra(Era era) {
        this.era = era;
        header.getStyleClass().setAll("panel-header", era.styleClass());
        area.getStyleClass().setAll("trace-console", era.styleClass());
        node.getStyleClass().setAll("trace-panel", era.styleClass());
        header.setText(era.shortLabel().toUpperCase(java.util.Locale.US) + "  ·  " + role);

        // The count keeps whatever state it is in; only its era changes.
        boolean zero = count.getStyleClass().contains("zero");
        boolean empty = count.getStyleClass().contains("empty");
        count.getStyleClass().setAll("trace-count", era.styleClass());
        setState(zero, empty);
    }

    /**
     * Put the count label in exactly one state, replacing whatever it was in.
     *
     * <p>A replacement rather than an add/remove pair, because {@link #setEra} re-applies these
     * classes every time it runs. The previous add/remove version accumulated duplicates — the
     * class list is a plain {@code ObservableList}, {@code setAll} plus {@code addAll} appends
     * a second {@code empty} on the next era change, and {@code remove} then drops only one of
     * them. The survivor goes on matching {@code .trace-count.empty}, which sits <em>after</em>
     * {@code .trace-count.zero} in the stylesheet and so wins the tie: a panel with a trace in
     * it and something to shout about renders muted and unstyled instead.
     */
    private void setState(boolean zero, boolean empty) {
        count.getStyleClass().removeAll("zero", "empty");
        if (zero) {
            count.getStyleClass().add("zero");
        }
        if (empty) {
            count.getStyleClass().add("empty");
        }
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Classify a raw trace and collapse any long run of framework frames.
     *
     * <p>Public and static so it can be exercised without a scene — this is the part with
     * the interesting edge cases, and it should not need a window to test.
     */
    public static List<Line> parse(String trace) {
        return parse(trace, OWN_PACKAGE);
    }

    /** As above, reading {@code ownMarker} as the frames belonging to the reader. */
    public static List<Line> parse(String trace, String ownMarker) {
        List<Line> classified = new ArrayList<>();
        for (String raw : trace.stripTrailing().lines().toList()) {
            classified.add(new Line(raw, kindOf(raw, ownMarker)));
        }
        return collapse(classified);
    }

    private static Kind kindOf(String raw, String ownMarker) {
        String line = raw.strip();
        if (line.startsWith("...")) {
            // The JDK's own "... 14 more" elision. Framework noise by definition.
            return Kind.FRAMEWORK;
        }
        if (!line.startsWith("at ")) {
            return Kind.HEADER;
        }
        if (line.contains(ownMarker)) {
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
        return (int) trace.lines()
                .filter(line -> kindOf(line, OWN_PACKAGE) == Kind.REQUEST).count();
    }

    // ------------------------------------------------------------------ rendering

    public void setTrace(String trace) {
        area.replaceText("");
        for (Line line : parse(trace, ownMarker)) {
            area.append(line.text() + "\n", line.kind().styleClass());
        }
        area.showParagraphAtTop(0);
        Footer footer = metric.measure(trace);
        count.setText(footer.text());
        setState(footer.emphasised(), false);
    }

    /** A fetch that did not get far enough to produce a trace. Stays inside the panel. */
    public void setError(String message) {
        area.replaceText("");
        area.append(message + "\n", Kind.HEADER.styleClass());
        count.setText("no trace");
        setState(false, true);
    }

    public void clear() {
        area.replaceText("");
        count.setText("—");
        setState(false, true);
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
