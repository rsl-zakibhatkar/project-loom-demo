package loomdemo.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
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
import loomdemo.DemoTab;
import loomdemo.Era;
import loomdemo.load.BreakProbe;
import loomdemo.load.LoadGenerator;
import loomdemo.load.RunResult;
import loomdemo.server.OrderServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab 3: run the same load test against the same server in each era and put the results
 * next to each other.
 *
 * <p>Past and present are the main comparison and are always on screen. The workaround
 * panel stays hidden until it has actually been run, so the tab opens as the two-panel
 * contrast the talk is built around and the third slides in only when the presenter
 * reaches for it.
 *
 * <p>The workaround will tie with virtual threads on throughput — that is what reactive
 * does, and pretending otherwise would be dishonest. Which is exactly why this tab can
 * also put the handlers on screen: once the numbers match, the code is the whole argument.
 */
public final class PerfCompareTab implements DemoTab {

    /** One request against a loopback server that fails on purpose. It is either instant
     * or something is badly wrong, so the timeout only has to be generous enough to not
     * misfire on a laptop that has just been woken up. */
    private static final Duration BREAK_TIMEOUT = Duration.ofSeconds(10);

    private static final List<Integer> REQUEST_OPTIONS = List.of(1_000, 5_000, 20_000);
    private static final List<Integer> CONCURRENCY_OPTIONS = List.of(100, 500, 2_000);

    private final OrderServer server = new OrderServer();
    private final LoadGenerator generator = new LoadGenerator();

    private final SegmentedPicker<Era> eraPicker = new SegmentedPicker<>(
            List.of(Era.values()), Era::label, Era.PAST, Era::styleClass);
    private final ComboBox<OrderServer.Endpoint> endpointBox = new ComboBox<>();

    /*
     * Buttons, not dropdowns. Three fixed numbers each, and a ComboBox popup is a native
     * window that intermittently paints blank on macOS until the mouse moves over it —
     * which is exactly the wrong failure to hit while a room is watching. See
     * SegmentedPicker. Endpoint stays a dropdown because its three labels are HTTP paths
     * and laying them out side by side would take the control row to three lines in
     * presentation mode.
     */
    private final SegmentedPicker<Integer> requestsPicker =
            new SegmentedPicker<>(REQUEST_OPTIONS, PerfCompareTab::thousands, 5_000);
    private final SegmentedPicker<Integer> concurrencyPicker =
            new SegmentedPicker<>(CONCURRENCY_OPTIONS, PerfCompareTab::thousands, 2_000);

    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset stats");
    private final ToggleButton handlersButton = new ToggleButton("Show the handlers");
    private final ToggleButton breakButton = new ToggleButton("Break it");

    private final Map<Era, StatsPanel> panels = new EnumMap<>(Era.class);
    private final Map<Era, XYChart.Series<Number, Number>> series = new EnumMap<>(Era.class);

    private final Label comparison = new Label();
    private final Label workaroundLine = new Label();
    private final Label status = new Label();
    private final Label mismatchWarning = new Label();

    private final JavaCodeArea handlerCode = new JavaCodeArea();
    private final Label handlerCodeHeader = new Label();
    private final VBox handlerBox;
    private final VBox chartBox;

    /**
     * The three things that can occupy the space below the panels. They are siblings that
     * take it in turns, so this has to be real state rather than two toggles guessing at
     * each other.
     */
    private enum View { CHART, HANDLERS, BREAK }

    private final TraceView blockingTrace = new TraceView(Era.PRESENT, "blocking");
    private final TraceView asyncTrace = new TraceView(Era.WORKAROUND, "async");
    private final Label breakLine = new Label();
    private final VBox breakBox;

    /**
     * Panels and comparison lines together, so the break view can reclaim their height in
     * one move. In presentation mode they push the traces off the bottom of the screen
     * entirely, and the traces are a different argument from the throughput numbers — the
     * numbers are still there the moment you go back to the chart.
     */
    private final VBox statsSection;

    /** Guards against {@code setSelected} re-entering the toggles' own listeners. */
    private boolean suppressViewSync;

    /** Bumped by every fetch and by Stop, so a late reply from an abandoned one is dropped. */
    private int breakGeneration;
    private boolean breaking;

    private final LineChart<Number, Number> chart;
    private final VBox node;

    public PerfCompareTab() {
        for (Era era : Era.values()) {
            panels.put(era, new StatsPanel(era));
            series.put(era, new XYChart.Series<>());
        }
        chart = buildChart();

        // Chronological order, so the story reads left to right.
        HBox panelRow = new HBox(12);
        for (Era era : Era.values()) {
            panelRow.getChildren().add(panels.get(era).getNode());
        }
        panelRow.setAlignment(Pos.TOP_CENTER);
        showWorkaroundPanel(false);

        comparison.getStyleClass().add("comparison-line");
        comparison.setMaxWidth(Double.MAX_VALUE);
        comparison.setAlignment(Pos.CENTER);
        hide(comparison);

        workaroundLine.getStyleClass().add("workaround-line");
        workaroundLine.setMaxWidth(Double.MAX_VALUE);
        workaroundLine.setAlignment(Pos.CENTER);
        workaroundLine.setWrapText(true);
        hide(workaroundLine);

        mismatchWarning.getStyleClass().add("warning-text");
        hide(mismatchWarning);

        // The three views carry a style class each. Nothing in the stylesheet needs them
        // today; they exist so "which view is on screen" is answerable from outside this
        // class, which is otherwise only inferable from three visibility flags.
        chartBox = new VBox(6, chartLegend(), chart);
        chartBox.getStyleClass().add("chart-view");
        VBox.setVgrow(chart, Priority.ALWAYS);
        VBox.setVgrow(chartBox, Priority.ALWAYS);
        chart.setMinHeight(200);

        handlerBox = buildHandlerView();
        handlerBox.getStyleClass().add("handler-view");
        hide(handlerBox);

        breakBox = buildBreakView();
        breakBox.getStyleClass().add("break-view");
        hide(breakBox);

        statsSection = new VBox(10, panelRow, comparison, workaroundLine);

        VBox content = new VBox(10, buildControls(), buildCaptions(), mismatchWarning,
                statsSection, chartBox, handlerBox, breakBox);
        content.setPadding(new Insets(12));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPannable(false);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        node = new VBox(scroller);

        runButton.getStyleClass().addAll("run-button", eraPicker.getValue().styleClass());
        eraPicker.valueProperty().addListener((obs, was, is) -> {
            runButton.getStyleClass().setAll("run-button", is.styleClass());
            refreshHandlerCode();
            refreshMismatchWarning();
        });
        refreshHandlerCode();

        setRunning(false);
    }

    private static void hide(javafx.scene.Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * The workaround is a reveal, not a default. The room sees two panels, hears "but I
     * would just use CompletableFuture", and only then does the third appear.
     */
    private void showWorkaroundPanel(boolean visible) {
        show(panels.get(Era.WORKAROUND).getNode(), visible);
    }

    /**
     * Exactly one of the chart, the handlers and the traces is on screen at a time.
     *
     * <p>Both toggles are driven from here rather than from each other. Setting one's
     * {@code selected} fires its own listener, so the sync is wrapped in a flag — the same
     * re-entry defence {@link SegmentedPicker} documents.
     */
    private void showView(View view) {
        show(chartBox, view == View.CHART);
        show(handlerBox, view == View.HANDLERS);
        show(breakBox, view == View.BREAK);

        // Only the break view reclaims the panels' height; the handlers view keeps them,
        // because the line-count argument is about the same run the numbers came from.
        show(statsSection, view != View.BREAK);

        suppressViewSync = true;
        handlersButton.setSelected(view == View.HANDLERS);
        breakButton.setSelected(view == View.BREAK);
        handlersButton.setText(view == View.HANDLERS ? "Show the chart" : "Show the handlers");
        breakButton.setText(view == View.BREAK ? "Show the chart" : "Break it");
        suppressViewSync = false;
    }

    // ------------------------------------------------------------------ controls

    private VBox buildControls() {
        endpointBox.getItems().setAll(OrderServer.Endpoint.values());
        endpointBox.getSelectionModel().select(OrderServer.Endpoint.NORMAL);
        endpointBox.setTooltip(new Tooltip(
                "Which handler to hit. Each one just sleeps for its latency and returns JSON."));

        Tooltip.install(requestsPicker.getNode(),
                new Tooltip("Total requests to send in the timed run."));
        Tooltip.install(concurrencyPicker.getNode(), new Tooltip(
                "How many requests are in flight at once.\n"
                        + "The contrast is sharpest when this is well above the server's "
                        + "200-thread pool."));

        handlersButton.getStyleClass().add("secondary-button");
        handlersButton.setTooltip(new Tooltip(
                "Swap the chart for the handler that era actually runs.\n"
                        + "Past and present share one; the workaround needs its own."));
        handlersButton.selectedProperty().addListener((o, was, is) -> {
            if (!suppressViewSync) {
                showView(is ? View.HANDLERS : View.CHART);
            }
        });

        breakButton.getStyleClass().add("secondary-button");
        breakButton.setTooltip(new Tooltip(
                "Send one request to an endpoint that always fails, through both handler "
                        + "shapes,\nand put the two stack traces side by side. "
                        + "No load test."));
        breakButton.selectedProperty().addListener((o, was, is) -> {
            if (suppressViewSync) {
                return;
            }
            showView(is ? View.BREAK : View.CHART);
            if (is) {
                fetchTraces();
            }
        });

        Runnable onConfigChange = this::refreshMismatchWarning;
        endpointBox.valueProperty().addListener((o, a, b) -> onConfigChange.run());
        requestsPicker.valueProperty().addListener((o, a, b) -> onConfigChange.run());
        concurrencyPicker.valueProperty().addListener((o, a, b) -> onConfigChange.run());

        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip("Run this config against the server  (⌘R)"));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Abandon the run  (⌘.)"));

        resetButton.getStyleClass().add("secondary-button");
        resetButton.setOnAction(e -> clearOutput());
        resetButton.setTooltip(new Tooltip("Empty both panels and the chart  (⌘K)"));

        status.getStyleClass().add("elapsed-timer");

        for (Region control : new Region[]{endpointBox, runButton, stopButton, resetButton,
                handlersButton, breakButton}) {
            // Never shrink a control below the width of its own text. Combined with the
            // FlowPane below, a too-narrow window wraps the row instead of turning every
            // label into "...".
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        FlowPane row = new FlowPane(10, 8,
                field("Endpoint", endpointBox),
                field("Requests", requestsPicker.getNode()),
                field("Concurrency", concurrencyPicker.getNode()),
                field("", eraPicker.getNode()),
                field("", runButton),
                field("", stopButton),
                field("", resetButton),
                field("", handlersButton),
                field("", breakButton));
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
        Label serverNote = new Label(
                "Each request calls three dummy services in turn — findUser, findOrder, "
                        + "chargeCard.");
        serverNote.getStyleClass().add("caption");
        serverNote.setTooltip(new Tooltip(
                "The services do nothing but wait, and they split the endpoint's latency "
                        + "rather than adding to it — a 100ms endpoint still takes 100ms.\n"
                        + "Each call needs the answer from the one before it, which is what "
                        + "the async handler has to work around.\n"
                        + "The handlers are identical in past and present. The only thing "
                        + "that changes is the executor the server runs them on: a fixed "
                        + "pool of 200 platform threads, or one virtual thread per request."));

        Label clientNote = new Label("Client always uses virtual threads ⓘ");
        clientNote.getStyleClass().add("caption");
        clientNote.setTooltip(new Tooltip(
                "The load generator runs one virtual thread per request in BOTH modes, so the "
                        + "client is never the bottleneck.\nIf it used a bounded platform-thread "
                        + "pool, the past-mode numbers would be measuring the test harness "
                        + "rather than the server."));

        HBox row = new HBox(16, serverNote, spacer(), status, clientNote);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private LineChart<Number, Number> buildChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("seconds into the run");
        xAxis.setForceZeroInRange(true);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("request latency (ms)");
        yAxis.setForceZeroInRange(true);

        LineChart<Number, Number> lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setCreateSymbols(false);
        lineChart.setLegendVisible(false);
        lineChart.setAnimated(false);   // animation fights a live-updating series
        lineChart.setTitle(null);

        // The built-in legend is hidden and we draw our own, but keep these honest anyway.
        for (Era era : Era.values()) {
            XYChart.Series<Number, Number> s = series.get(era);
            s.setName(era.shortLabel());
            lineChart.getData().add(s);
            styleSeries(s, era);
        }
        return lineChart;
    }

    /** JavaFX hands series rotating default-colour classes, so pin the stroke directly. */
    private static void styleSeries(XYChart.Series<Number, Number> series, Era era) {
        if (series.getNode() != null) {
            series.getNode().setStyle("-fx-stroke: " + era.accent() + "; -fx-stroke-width: 2.5;");
        }
    }

    private HBox chartLegend() {
        List<javafx.scene.Node> items = new ArrayList<>();
        for (Era era : Era.values()) {
            items.add(legendItem(era));
        }
        HBox legend = new HBox(16);
        legend.getChildren().addAll(items);
        legend.setAlignment(Pos.CENTER_LEFT);
        return legend;
    }

    private static Label legendItem(Era era) {
        Label label = new Label("●  " + era.shortLabel());
        label.getStyleClass().add("caption");
        label.setStyle("-fx-text-fill: " + era.accent() + "; -fx-font-weight: bold;");
        return label;
    }

    // ------------------------------------------------------------- handler code view

    /**
     * The counterweight to the numbers. Async ties with virtual threads on throughput, so
     * a panel showing only req/s would quietly argue against Loom. This shows what each
     * era costs to write, driven by the same buttons that drive the runs.
     */
    private VBox buildHandlerView() {
        handlerCodeHeader.getStyleClass().add("handler-code-header");
        handlerCode.setEditable(false);

        Region codeNode = handlerCode.getNode();
        codeNode.getStyleClass().add("editor-frame");
        codeNode.setMinHeight(240);
        VBox.setVgrow(codeNode, Priority.ALWAYS);

        VBox box = new VBox(6, handlerCodeHeader, codeNode);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildBreakView() {
        breakLine.getStyleClass().add("break-line");
        breakLine.setMaxWidth(Double.MAX_VALUE);
        breakLine.setAlignment(Pos.CENTER);
        breakLine.setWrapText(true);
        hide(breakLine);

        HBox traceRow = new HBox(12, blockingTrace.getNode(), asyncTrace.getNode());
        traceRow.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(traceRow, Priority.ALWAYS);

        VBox box = new VBox(10, traceRow, breakLine);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * The blocking side of the break view follows the era picker between past and present,
     * and stands on present when the workaround itself is selected — past and present run
     * the same boom handler, so there is no third trace to show.
     */
    private Era blockingEra() {
        return eraPicker.getValue() == Era.PAST ? Era.PAST : Era.PRESENT;
    }

    /**
     * One request per handler shape, no load test, and no server restart that can be
     * avoided: whichever era the server is already bound to goes first, so a warm server
     * costs nothing and the worst case is a single rebind.
     *
     * <p>This deliberately does not require a prior load run. A stack trace does not depend
     * on one, and gating it would make the button silently do nothing on a fresh tab.
     */
    private void fetchTraces() {
        if (generator.isRunning() || breaking) {
            return;
        }
        Era blocking = blockingEra();
        blockingTrace.setEra(blocking);
        blockingTrace.clear();
        asyncTrace.clear();
        hide(breakLine);

        breaking = true;
        int generation = ++breakGeneration;
        setRunning(false);
        status.setText("breaking it…");

        Era runningNow = server.runningEra();
        List<Era> order = runningNow == Era.WORKAROUND
                ? List.of(Era.WORKAROUND, blocking)
                : List.of(blocking, Era.WORKAROUND);

        Thread fetcher = new Thread(() -> {
            Map<Era, String> traces = new EnumMap<>(Era.class);
            Map<Era, String> failures = new EnumMap<>(Era.class);
            for (Era era : order) {
                try {
                    server.startFor(era);
                    traces.put(era, BreakProbe.fetchTrace(server.port(), BREAK_TIMEOUT));
                } catch (Throwable t) {
                    failures.put(era, "Could not reach the server: " + t);
                }
            }
            Platform.runLater(() -> {
                if (generation != breakGeneration) {
                    return;   // stopped, or a newer fetch already started
                }
                breaking = false;
                status.setText("");
                setRunning(false);
                applyTrace(blockingTrace, blocking, traces, failures);
                applyTrace(asyncTrace, Era.WORKAROUND, traces, failures);
                refreshBreakLine(traces.get(blocking), traces.get(Era.WORKAROUND));
            });
        }, "break-it");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    /** Failures land in the panel itself — {@code mismatchWarning} is shared and volatile. */
    private static void applyTrace(TraceView view, Era era, Map<Era, String> traces,
                                   Map<Era, String> failures) {
        String trace = traces.get(era);
        if (trace != null && !trace.isBlank()) {
            view.setTrace(trace);
        } else {
            view.setError(failures.getOrDefault(era, "No trace came back."));
        }
    }

    private void refreshBreakLine(String blocking, String async) {
        boolean both = blocking != null && !blocking.isBlank()
                && async != null && !async.isBlank();
        show(breakLine, both);
        if (both) {
            breakLine.setText("Same failure. One trace tells you where.");
        }
    }

    private void refreshHandlerCode() {
        Era era = eraPicker.getValue();
        handlerCode.loadSource(OrderServer.handlerSourceFor(era));
        handlerCodeHeader.setText(String.format(Locale.US,
                "%s  ·  OrderServer handler  ·  %d lines of code",
                era.shortLabel().toUpperCase(Locale.US),
                OrderServer.handlerLineCount(era)));
    }

    private static String thousands(Integer value) {
        return value == null ? "" : String.format(Locale.US, "%,d", value);
    }

    // ----------------------------------------------------------------- execution

    @Override
    public void runDemo() {
        if (generator.isRunning()) {
            return;
        }
        Era era = eraPicker.getValue();
        OrderServer.Endpoint endpoint = endpointBox.getValue();
        int total = requestsPicker.getValue();
        int concurrency = concurrencyPicker.getValue();

        setRunning(true);
        status.setText("starting server…");
        series.get(era).getData().clear();

        // Server start (and restart on a mode change) is off the FX thread; the generator
        // itself must be kicked off back on it because it drives a Timeline.
        Thread starter = new Thread(() -> {
            try {
                server.startFor(era);
                int port = server.port();
                Platform.runLater(() ->
                        generator.start(era, port, endpoint, total, concurrency, listener(era)));
            } catch (Throwable t) {
                Platform.runLater(() -> {
                    setRunning(false);
                    status.setText("");
                    showWarning("Could not start the server: " + t);
                });
            }
        }, "server-start");
        starter.setDaemon(true);
        starter.start();
    }

    private LoadGenerator.Listener listener(Era era) {
        return new LoadGenerator.Listener() {
            @Override
            public void onSamples(List<LoadGenerator.Sample> batch) {
                XYChart.Series<Number, Number> line = series.get(era);
                for (LoadGenerator.Sample sample : batch) {
                    line.getData().add(
                            new XYChart.Data<>(sample.elapsedSeconds(), sample.latencyMillis()));
                }
                styleSeries(line, era);
            }

            @Override
            public void onProgress(LoadGenerator.Phase phase, int completed, int total,
                                   long elapsedMillis) {
                if (phase == LoadGenerator.Phase.WARMUP) {
                    status.setText("warming up connections…");
                } else {
                    status.setText(String.format(Locale.US, "%,d / %,d   ·   %.1fs",
                            completed, total, elapsedMillis / 1000.0));
                }
            }

            @Override
            public void onDone(RunResult result) {
                setRunning(false);
                status.setText("");
                panels.get(era).setResult(result);
                if (era == Era.WORKAROUND) {
                    showWorkaroundPanel(true);
                }
                refreshComparison();
                refreshMismatchWarning();
            }

            @Override
            public void onStopped(int completed) {
                setRunning(false);
                status.setText("");
                series.get(era).getData().clear();
                showWarning(String.format(Locale.US,
                        "Run stopped after %,d requests — the %s panel still shows its last "
                                + "complete run.", completed,
                        era.shortLabel()));
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                status.setText("");
                showWarning(message);
            }
        };
    }

    /** The sentence the audience remembers. Only shown once past and present have run. */
    private void refreshComparison() {
        RunResult past = panels.get(Era.PAST).getResult();
        RunResult present = panels.get(Era.PRESENT).getResult();
        RunResult workaround = panels.get(Era.WORKAROUND).getResult();

        boolean both = past != null && present != null;
        show(comparison, both);
        if (both) {
            double ratio = past.throughput() <= 0 ? 0 : present.throughput() / past.throughput();
            comparison.setText(String.format(Locale.US,
                    "Present: %.1f× throughput,  p99 latency %,d ms → %,d ms",
                    ratio, past.p99(), present.p99()));
        }

        // The second line is the one that answers "why not just use CompletableFuture?".
        // It only appears once the room has actually seen async match, because until then
        // it would be a claim rather than a measurement.
        boolean showWorkaround = workaround != null && present != null;
        show(workaroundLine, showWorkaround);
        if (showWorkaround) {
            // Callbacks, not lines. Once both handlers share respond(), the async one is
            // actually the SHORTER of the two — so a line count would now argue the wrong
            // way, and propping it up would mean billing async for boilerplate blocking
            // needs too. What survives the fair comparison is the control flow.
            int asyncCallbacks = OrderServer.handlerCallbackCount(Era.WORKAROUND);
            int blockingCallbacks = OrderServer.handlerCallbackCount(Era.PRESENT);
            workaroundLine.setText(String.format(Locale.US,
                    "Async got there too — %,.0f req/s on %d threads, against %,.0f. "
                            + "It cost %d callbacks instead of %d, two of them nested.",
                    workaround.throughput(), Era.asyncThreads(), present.throughput(),
                    asyncCallbacks, blockingCallbacks));
        }
    }

    /**
     * Inline, never a popup — a modal dialog mid-talk is worse than the mistake it warns
     * about.
     */
    private void refreshMismatchWarning() {
        List<RunResult> results = new ArrayList<>();
        for (Era era : Era.values()) {
            RunResult result = panels.get(era).getResult();
            if (result != null) {
                results.add(result);
            }
        }
        RunResult first = results.isEmpty() ? null : results.get(0);
        boolean mismatched = results.stream().anyMatch(r -> !r.sameConfigAs(first));
        if (mismatched) {
            showWarning("⚠  These panels were run with different settings, so the "
                    + "comparison is not valid. Re-run the odd one out with matching "
                    + "settings.");
        } else {
            hideWarning();
        }
    }

    private void showWarning(String message) {
        mismatchWarning.setText(message);
        show(mismatchWarning, true);
    }

    private void hideWarning() {
        hide(mismatchWarning);
    }

    private void setRunning(boolean running) {
        // A break fetch is short, but it rebinds the server, so it locks the same controls
        // a load run does. Stop stays live throughout so either can be abandoned.
        boolean busy = running || breaking;
        runButton.setDisable(busy);
        stopButton.setDisable(!busy);
        resetButton.setDisable(busy);
        eraPicker.setDisable(busy);
        endpointBox.setDisable(busy);
        requestsPicker.setDisable(busy);
        concurrencyPicker.setDisable(busy);
        breakButton.setDisable(running);
    }

    @Override
    public void stopDemo() {
        generator.stop();
        // Bumping the generation orphans an in-flight break fetch: it will still finish its
        // requests, but its reply is dropped rather than landing on a tab that moved on.
        breakGeneration++;
        if (breaking) {
            breaking = false;
            status.setText("");
            setRunning(false);
        }
    }

    /** ⌘K here means "reset stats" — the panels, the chart, the lines and the traces. */
    @Override
    public void clearOutput() {
        if (generator.isRunning() || breaking) {
            return;
        }
        for (Era era : Era.values()) {
            panels.get(era).clear();
            series.get(era).getData().clear();
        }
        showWorkaroundPanel(false);
        hide(comparison);
        hide(workaroundLine);
        hideWarning();
        blockingTrace.clear();
        asyncTrace.clear();
        hide(breakLine);
        showView(View.CHART);
        status.setText("");
    }

    @Override
    public void shutdown() {
        generator.stop();
        server.stop();
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
