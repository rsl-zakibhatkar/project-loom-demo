package loomdemo.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import loomdemo.DemoTab;
import loomdemo.Era;
import loomdemo.Shape;
import loomdemo.load.FrameRecorder;
import loomdemo.load.FrameRun;
import loomdemo.server.OrderServer;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab 4: one blocking call, frame by frame.
 *
 * <p>The talk's slide of the same name is four drawn boxes — VT-1 mounted on CT-1, VT-1
 * unmounted with its stack on the heap, CT-1 picking up somebody else, VT-1 resuming
 * somewhere different. It is the one claim in the deck that is asserted rather than shown,
 * and everything after it rests on it. This tab runs the order service for real and makes
 * those four boxes measured facts with the machine's own numbers in them.
 *
 * <p>Structurally this follows {@link PerfCompareTab}: work on named daemon threads with
 * callbacks marshalled back to FX, an explicit {@code View} enum rather than toggles guessing
 * at each other, and inline warnings instead of dialogs.
 *
 * <p>Three shapes, and no workaround era. {@link Era#WORKAROUND} is deliberately absent: the
 * async handler has no thread to follow — its three calls happen on whichever pool worker is
 * free, which is the argument the Break it view already makes. Following a thread through a
 * callback chain would be a fourth exhibit pretending to be this one.
 *
 * <p>{@link Shape#PINNED} is the third button, and it is the same era as the second: virtual
 * threads, the same executor, the same three calls, with one {@code synchronized} around
 * them. On JDK 21 that is enough to stop the thread unmounting, so the carrier rows go red
 * and the board that proved the story at slide 33 disproves it at slide 40 — which is exactly
 * why it is a button here rather than a tab of its own. There is no fourth button for
 * {@code ReentrantLock}: a lock-guarded call unmounts like an unguarded one, so the second
 * button already draws that picture, and the code view says so.
 */
public final class FrameByFrameTab implements DemoTab {

    /**
     * The largest option is scaled to the machine so the stall cannot quietly vanish on a
     * bigger one: three times the carriers means at least three passes before the last
     * request gets one. On the eight-core machine this was built for, that is the same 24 it
     * has always been.
     */
    private static final List<Integer> REQUEST_OPTIONS =
            List.of(3, 8, Math.max(24, 3 * Runtime.getRuntime().availableProcessors()));

    private static final Integer BUSY_REQUESTS = REQUEST_OPTIONS.get(2);

    /**
     * Playback pacing. Real gaps are used where they are watchable and clamped where they are
     * not — a 20 ms endpoint would otherwise flash the whole story past in a quarter second,
     * and a 300 ms one would leave the room looking at a still.
     */
    private static final double MIN_STEP_MILLIS = 260;
    private static final double MAX_STEP_MILLIS = 1_400;

    private static final String STACK_TOOLTIP =
            "Captured from outside the thread, while it was in the middle of its wait.\n"
                    + "The frames are all there either way. What differs is whether an "
                    + "operating-system thread had to stay alive to hold them.";

    private final FrameRecorder recorder = new FrameRecorder();

    private final SegmentedPicker<Shape> shapePicker = new SegmentedPicker<>(
            List.of(Shape.PLATFORM, Shape.VIRTUAL, Shape.PINNED), Shape::label,
            Shape.VIRTUAL, Shape::styleClass);
    private final SegmentedPicker<Integer> requestsPicker =
            new SegmentedPicker<>(REQUEST_OPTIONS, String::valueOf, BUSY_REQUESTS);
    private final ComboBox<OrderServer.Endpoint> endpointBox = new ComboBox<>();

    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset");
    private final Button prevButton = new Button("◀");
    private final Button nextButton = new Button("Next step  ▶");
    private final Button playButton = new Button("▶  Play");
    private final Label stepLabel = new Label();
    private final ToggleButton stackButton = new ToggleButton("Show the parked stack");
    private final ToggleButton codeButton = new ToggleButton("Show the code");

    private final Label status = new Label();
    private final Label warning = new Label();
    private final Label headline = new Label();

    private final MountBoard board = new MountBoard();
    private final StreamConsole console = new StreamConsole();

    /**
     * The snapshots feeding the two stack panels, kept per shape so the comparison survives
     * the runs being minutes apart — the same reason {@link PerfCompareTab} keeps a stats
     * panel per era rather than one that is overwritten. Three shapes, two panels: virtual is
     * always on the left as the reference, and the right-hand panel is whichever of the two
     * ways it can go wrong the picker is on.
     */
    private final Map<Shape, FrameRun.ParkedStack> snapshots = new EnumMap<>(Shape.class);

    /** Which shape the right-hand panel is showing; held while the picker sits on virtual. */
    private Shape otherShape = Shape.PLATFORM;

    private final TraceView virtualStack = new TraceView(Era.PRESENT,
            Shape.VIRTUAL.stackRole(), trace -> footerFor(Shape.VIRTUAL), STACK_TOOLTIP);
    private final TraceView otherStack = new TraceView(Era.PAST,
            Shape.PLATFORM.stackRole(), trace -> footerFor(otherShape), STACK_TOOLTIP);
    private final Label stackLine = new Label();

    private final JavaCodeArea codeArea = new JavaCodeArea();
    private final Label codeHeader = new Label();

    private final VBox boardBox;
    private final VBox stackBox;
    private final VBox codeBox;

    /** The three things that can occupy the space below the headline. */
    private enum View { BOARD, STACK, CODE }

    /** Guards against {@code setSelected} re-entering the toggles' own listeners. */
    private boolean suppressViewSync;

    private FrameRun run;
    private List<FrameRun.Step> steps = List.of();
    private int stepIndex = -1;
    private int runCount;
    private Timeline player;

    private final VBox node;

    public FrameByFrameTab() {
        headline.getStyleClass().add("comparison-line");
        headline.setMaxWidth(Double.MAX_VALUE);
        headline.setAlignment(Pos.CENTER);
        headline.setWrapText(true);
        headline.setMinHeight(Region.USE_PREF_SIZE);   // a wrapped label must not be clipped
        hide(headline);

        warning.getStyleClass().add("warning-text");
        warning.setWrapText(true);
        hide(warning);

        boardBox = buildBoardView();
        boardBox.getStyleClass().add("board-view");

        stackBox = buildStackView();
        stackBox.getStyleClass().add("stack-view");
        hide(stackBox);

        codeBox = buildCodeView();
        codeBox.getStyleClass().add("code-view");
        hide(codeBox);

        VBox content = new VBox(10, buildControls(), buildCaptions(), warning, headline,
                boardBox, stackBox, codeBox);
        content.setPadding(new Insets(12));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        // Deliberately NOT fitToHeight: the board view is taller than any window at 24
        // requests, and forcing the content to the viewport height silently squeezes the
        // step log out of existence and clips the wrapped headline. The two short views set
        // their own pref heights instead.
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPannable(false);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        node = new VBox(scroller);

        runButton.getStyleClass().addAll("run-button", shapePicker.getValue().styleClass());
        shapePicker.valueProperty().addListener((obs, was, is) -> {
            runButton.getStyleClass().setAll("run-button", is.styleClass());
            codeArea.loadSource(FrameRecorder.sourceFor(is));
            codeHeader.setText(codeHeaderFor(is));
            if (is != Shape.VIRTUAL) {
                otherShape = is;
                otherStack.setRole(is.stackRole());
                otherStack.setEra(is.era());
                refreshStacks();
            }
        });

        refreshStacks();
        setRunning(false);
        updateStepControls();
    }

    private static void hide(javafx.scene.Node target) {
        target.setVisible(false);
        target.setManaged(false);
    }

    private static void show(javafx.scene.Node target, boolean visible) {
        target.setVisible(visible);
        target.setManaged(visible);
    }

    /** Exactly one of the board, the stacks and the code is on screen at a time. */
    private void showView(View view) {
        show(boardBox, view == View.BOARD);
        show(stackBox, view == View.STACK);
        show(codeBox, view == View.CODE);

        // The headline is about the board's run and means nothing over a stack or a listing.
        show(headline, view == View.BOARD && run != null);

        suppressViewSync = true;
        stackButton.setSelected(view == View.STACK);
        codeButton.setSelected(view == View.CODE);
        stackButton.setText(view == View.STACK ? "Show the board" : "Show the parked stack");
        codeButton.setText(view == View.CODE ? "Show the board" : "Show the code");
        suppressViewSync = false;
    }

    // ------------------------------------------------------------------ controls

    private VBox buildControls() {
        endpointBox.getItems().setAll(OrderServer.Endpoint.values());
        endpointBox.getSelectionModel().select(OrderServer.Endpoint.SLOW);
        endpointBox.setTooltip(new Tooltip(
                "How long the three services wait in total, split 40/35/25 between them — "
                        + "the same budget the Performance Comparison tab uses.\n"
                        + "Longer waits make the gaps on the board easier to read from the "
                        + "back of the room."));

        Tooltip.install(requestsPicker.getNode(), new Tooltip(
                "How many requests run at once.\n"
                        + "More requests than the machine has carriers is what makes one get "
                        + "reused the instant a request lets go of it — which is STEP 03. At "
                        + "3 requests nothing is queued, and the step log says so instead.\n"
                        + "It is also what decides whether virtual + synchronized stalls at "
                        + "all: with a carrier free for every request, a pin costs nothing "
                        + "yet. That is why the bug reaches production."));

        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip("Record a run  (⌘R)"));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Abandon the recording  (⌘.)"));

        resetButton.getStyleClass().add("secondary-button");
        resetButton.setOnAction(e -> clearOutput());
        resetButton.setTooltip(new Tooltip("Empty the board and the step log  (⌘K)"));

        prevButton.getStyleClass().add("secondary-button");
        prevButton.setOnAction(e -> {
            stopPlayer();
            showStepsUpTo(stepIndex - 1);
        });
        prevButton.setTooltip(new Tooltip("Back one step"));

        nextButton.getStyleClass().add("secondary-button");
        nextButton.setOnAction(e -> {
            stopPlayer();
            showStepsUpTo(stepIndex + 1);
        });
        nextButton.setTooltip(new Tooltip(
                "Walk the run one step at a time; the board follows.\n"
                        + "The first four steps are the slide, in order — the rest are the "
                        + "same thing happening for the other two calls."));

        playButton.getStyleClass().add("secondary-button");
        playButton.setOnAction(e -> play());
        playButton.setTooltip(new Tooltip(
                "Walk every step on its own, roughly in proportion to the real gaps."));

        stepLabel.getStyleClass().add("elapsed-timer");

        stackButton.getStyleClass().add("secondary-button");
        stackButton.setTooltip(new Tooltip(
                "The stack of a request captured while it was waiting, in both eras.\n"
                        + "Same frames. The line underneath is the whole difference."));
        stackButton.selectedProperty().addListener((o, was, is) -> {
            if (!suppressViewSync) {
                showView(is ? View.STACK : View.BOARD);
            }
        });

        codeButton.getStyleClass().add("secondary-button");
        codeButton.setTooltip(new Tooltip(
                "The eight marks the board is built from, and how the carrier is read."));
        codeButton.selectedProperty().addListener((o, was, is) -> {
            if (!suppressViewSync) {
                showView(is ? View.CODE : View.BOARD);
            }
        });

        for (Region control : new Region[]{endpointBox, runButton, stopButton, resetButton,
                prevButton, nextButton, playButton, stackButton, codeButton}) {
            // Never shrink a control below its own label; the FlowPane wraps instead.
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        FlowPane row = new FlowPane(10, 8,
                field("Requests", requestsPicker.getNode()),
                field("Simulated I/O", endpointBox),
                field("", shapePicker.getNode()),
                field("", runButton),
                field("", stopButton),
                field("", resetButton),
                field("", prevButton),
                field("", nextButton),
                field("", playButton),
                field("", stepLabel),
                field("", stackButton),
                field("", codeButton));
        row.setAlignment(Pos.BOTTOM_LEFT);
        return new VBox(row);
    }

    private static VBox field(String labelText, Region control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("section-label");
        label.setVisible(!labelText.isEmpty());
        label.setManaged(!labelText.isEmpty());
        VBox box = new VBox(3, label, control);
        box.setAlignment(Pos.BOTTOM_LEFT);
        return box;
    }

    private HBox buildCaptions() {
        Label serviceNote = new Label(
                "The same three services the load test hits — findUser, findOrder, chargeCard.");
        serviceNote.getStyleClass().add("caption");
        serviceNote.setTooltip(new Tooltip(
                "Called directly rather than over HTTP. The server is not the subject here: "
                        + "one request is,\nand a load generator in front of it would only put "
                        + "frames between you and the thing to watch.\n"
                        + "Under past, the pool is sized to the request count — one platform "
                        + "thread each, so nothing queues.\nQueueing is the Performance "
                        + "Comparison tab's argument; what is left here is what one blocking "
                        + "call costs."));

        Label eraNote = new Label("Three shapes, no workaround era ⓘ");
        eraNote.getStyleClass().add("caption");
        eraNote.setTooltip(new Tooltip(
                "platform threads and virtual threads are the past and the present.\n"
                        + "virtual + synchronized is the present with one keyword around the "
                        + "same three calls — and every request takes a monitor of its own, "
                        + "so nothing ever contends for one.\n\n"
                        + "The async workaround has no thread to follow: its three calls run "
                        + "on whichever pool worker is free.\nThat is the Break it view's "
                        + "argument, on the Performance Comparison tab. Following a callback "
                        + "chain here would be a different exhibit wearing this one's "
                        + "clothes."));

        HBox row = new HBox(16, serviceNote, spacer(), status, eraNote);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------ views

    private VBox buildBoardView() {
        console.setClearAction(this::clearOutput);
        // Tight on purpose. Twenty-four request lanes plus eight carrier lanes plus this
        // console is already a window's worth; every pixel here is one the board loses.
        Region consoleNode = console.getNode();
        consoleNode.setMinHeight(150);
        consoleNode.setPrefHeight(170);

        // Log above board, not below. At twenty-four requests the board is taller than the
        // window, and the STEP lines are the thing being read aloud — they have to stay on
        // screen while the presenter clicks. This also puts the focus request's lane directly
        // under the sentence describing it.
        VBox box = new VBox(10, consoleNode, board.getNode());
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildStackView() {
        stackLine.getStyleClass().add("break-line");
        stackLine.setMaxWidth(Double.MAX_VALUE);
        stackLine.setAlignment(Pos.CENTER);
        stackLine.setWrapText(true);
        hide(stackLine);

        HBox row = new HBox(12, virtualStack.getNode(), otherStack.getNode());
        row.setAlignment(Pos.TOP_CENTER);
        row.setPrefHeight(560);   // the stacks are the exhibit; give them the screen
        VBox.setVgrow(row, Priority.ALWAYS);

        VBox box = new VBox(10, row, stackLine);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildCodeView() {
        codeHeader.getStyleClass().add("handler-code-header");
        codeHeader.setText(codeHeaderFor(shapePicker.getValue()));
        codeArea.setEditable(false);
        codeArea.loadSource(FrameRecorder.sourceFor(shapePicker.getValue()));

        Region codeNode = codeArea.getNode();
        codeNode.getStyleClass().add("editor-frame");
        codeNode.setMinHeight(300);
        codeNode.setPrefHeight(620);   // the whole listing, comments included, without scrolling
        VBox.setVgrow(codeNode, Priority.ALWAYS);

        VBox box = new VBox(6, codeHeader, codeNode);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private static String codeHeaderFor(Shape shape) {
        return shape.guarded()
                ? "The same eight marks, inside one synchronized block  ·  one line different"
                : "What is actually recorded  ·  eight marks per request";
    }

    /**
     * The line under a captured stack. The frames are near-identical in every shape; this is
     * the only thing that differs, so it is the only thing the panel measures.
     *
     * <p>Pinning gets its own wording rather than borrowing the platform one. "An OS thread is
     * holding this stack" is true of both, and saying only that would lose the whole point:
     * under {@link Shape#PLATFORM} the thread holding it is the request's own and always was,
     * and under {@link Shape#PINNED} it is a carrier that was supposed to have been handed
     * back.
     */
    private TraceView.Footer footerFor(Shape shape) {
        FrameRun.ParkedStack snapshot = snapshots.get(shape);
        if (snapshot == null) {
            return new TraceView.Footer("no snapshot", false);
        }
        if (shape == Shape.PINNED) {
            return new TraceView.Footer(
                    "Carrier still held while waiting:  " + snapshot.holder(), true);
        }
        return snapshot.held()
                ? new TraceView.Footer("OS thread holding this stack:  " + snapshot.holder(), true)
                : new TraceView.Footer("OS thread holding this stack:  none", false);
    }

    private void refreshStacks() {
        applyStack(virtualStack, Shape.VIRTUAL);
        applyStack(otherStack, otherShape);

        boolean both = snapshots.get(Shape.VIRTUAL) != null && snapshots.get(otherShape) != null;
        show(stackLine, both);
        if (both) {
            stackLine.setText(otherShape == Shape.PINNED
                    ? "Same frames, same era, same kind of thread. One of them is still "
                            + "holding a carrier."
                    : "Same frames. Only one of them costs an OS thread.");
        }
    }

    private void applyStack(TraceView view, Shape shape) {
        FrameRun.ParkedStack snapshot = snapshots.get(shape);
        if (snapshot == null) {
            view.setError("Run \"" + shape.label() + "\" to capture this.");
        } else {
            view.setTrace(snapshot.stack());
        }
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Show the step log up to {@code index} and uncover the board to the same instant.
     *
     * <p>Rebuilt from scratch each time rather than appended to, because stepping goes
     * backwards as well — and a presenter who has just over-clicked wants the line gone, not
     * a second copy of the log underneath it.
     */
    private void showStepsUpTo(int index) {
        if (run == null || steps.isEmpty()) {
            return;
        }
        stepIndex = Math.max(-1, Math.min(steps.size() - 1, index));

        console.clear();
        console.appendDivider(runHeader());
        if (stepIndex < 0) {
            console.appendLine("Press  Next step ▶  — the first four lines are the slide.",
                    "step-hint");
        }
        for (int i = 0; i <= stepIndex; i++) {
            FrameRun.Step step = steps.get(i);
            console.appendLine(String.format(Locale.US, "STEP %02d   %s",
                    step.number(), step.text()), step.styleClass());
        }

        console.scrollToStart();

        if (stepIndex < 0) {
            // No walk in progress: show the whole recording. A board that stayed blank until
            // somebody clicked would read as a broken demo, and the picture is worth having
            // on screen while the presenter is still talking about the run.
            board.revealAll();
        } else if (stepIndex == steps.size() - 1) {
            board.revealAll();
        } else {
            board.revealUpTo(steps.get(stepIndex).nanos());
        }
        updateStepControls();
    }

    private String runHeader() {
        return String.format(Locale.US, "──── Run #%d  ·  %s  ·  %,d requests  ·  %d ms ────",
                runCount, run.shape().shortLabel(), run.requests(), run.latencyMillis());
    }

    private void updateStepControls() {
        boolean have = run != null && !steps.isEmpty() && !recorder.isRunning();
        prevButton.setDisable(!have || stepIndex < 0);
        nextButton.setDisable(!have || stepIndex >= steps.size() - 1);
        playButton.setDisable(!have);

        // The first click winds the board back to the start of the run, so it must not be
        // labelled "next" — a board that suddenly empties on a button called Next reads as a
        // bug rather than as the beginning of the walk.
        nextButton.setText(stepIndex < 0 ? "Walk the steps  ▶" : "Next step  ▶");
        stepLabel.setText(have
                ? String.format(Locale.US, "step %s / %d",
                        stepIndex < 0 ? "—" : String.valueOf(stepIndex + 1), steps.size())
                : "");
    }

    private void play() {
        if (run == null || steps.isEmpty()) {
            return;
        }
        stopPlayer();
        showStepsUpTo(-1);

        Timeline timeline = new Timeline();
        double at = 0;
        long previous = run.t0();
        for (int i = 0; i < steps.size(); i++) {
            long nanos = steps.get(i).nanos();
            double real = (nanos - previous) / 1_000_000.0;
            at += Math.max(MIN_STEP_MILLIS, Math.min(MAX_STEP_MILLIS, real));
            previous = nanos;
            int index = i;
            timeline.getKeyFrames().add(
                    new KeyFrame(Duration.millis(at), e -> showStepsUpTo(index)));
        }
        player = timeline;
        timeline.play();
    }

    private void stopPlayer() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }

    // ------------------------------------------------------------------ execution

    @Override
    public void runDemo() {
        if (recorder.isRunning()) {
            return;
        }
        stopPlayer();
        hideWarning();
        runCount++;
        setRunning(true);
        status.setText("recording…");
        showView(View.BOARD);

        recorder.start(shapePicker.getValue(), requestsPicker.getValue(),
                endpointBox.getValue().latencyMillis(), listener());
    }

    private FrameRecorder.Listener listener() {
        return new FrameRecorder.Listener() {
            @Override
            public void onProgress(int completed, int total) {
                status.setText(String.format(Locale.US, "%,d / %,d", completed, total));
            }

            @Override
            public void onDone(FrameRun recorded) {
                setRunning(false);
                status.setText("");
                apply(recorded);
            }

            @Override
            public void onStopped(int completed) {
                setRunning(false);
                status.setText("");
                showWarning(String.format(Locale.US,
                        "Recording stopped after %,d requests — the board still shows the "
                                + "last complete run.", completed));
                updateStepControls();
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                status.setText("");
                showWarning(message);
                updateStepControls();
            }
        };
    }

    private void apply(FrameRun recorded) {
        run = recorded;
        steps = recorded.steps();

        board.setRun(recorded);
        headline.setText(recorded.headline());
        show(headline, true);

        if (recorded.parked() != null) {
            snapshots.put(recorded.shape(), recorded.parked());
        }
        refreshStacks();

        showStepsUpTo(-1);

        // Said out loud rather than silently tolerated: with fewer requests than the machine
        // has cores nothing is ever queued for a carrier, so the step log has no reuse to
        // report and STEP 03 has to say "went idle" instead.
        if (recorded.pinned() && recorded.probe() != null && !recorded.probe().starved()) {
            // Measured off the probe rather than off the request count: at one request per
            // carrier nothing queues and every request still finishes on time, and the run is
            // a stall all the same — the next request to arrive is the one that finds out.
            showWarning(String.format(Locale.US,
                    "Only %d of this machine's carriers were taken, so there were still free "
                            + "ones and the pin has cost nothing yet. Run again at %,d "
                            + "requests to take them all.",
                    recorded.pinnedCarriers().size(), BUSY_REQUESTS));
        } else if (!recorded.pinned() && recorded.era() == Era.PRESENT
                && recorded.hops(recorded.focusRequest()) <= 1) {
            showWarning(String.format(Locale.US,
                    "No carrier hop was recorded at this size — the scheduler had a free "
                            + "carrier every time. Run again at %,d requests to see one.",
                    BUSY_REQUESTS));
        } else if (recorded.parked() == null) {
            showWarning("No stack could be captured mid-wait this run — the parked stack view "
                    + "still shows the last one that was.");
        }
    }

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
        shapePicker.setDisable(running);
        requestsPicker.setDisable(running);
        endpointBox.setDisable(running);
        updateStepControls();
    }

    @Override
    public void stopDemo() {
        stopPlayer();
        recorder.stop();
        setRunning(false);
        status.setText("");
    }

    /** ⌘K here means "empty the board" — the lanes, the log, the headline and the warning. */
    @Override
    public void clearOutput() {
        if (recorder.isRunning()) {
            return;
        }
        stopPlayer();
        run = null;
        steps = List.of();
        stepIndex = -1;
        board.clear();
        console.clear();
        hide(headline);
        hideWarning();
        snapshots.clear();
        refreshStacks();
        showView(View.BOARD);
        status.setText("");
        updateStepControls();
    }

    @Override
    public void shutdown() {
        stopPlayer();
        recorder.stop();
    }

    public VBox getNode() {
        return node;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
