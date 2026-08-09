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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import loomdemo.DemoTab;
import loomdemo.Mode;
import loomdemo.load.LoadGenerator;
import loomdemo.load.RunResult;
import loomdemo.server.OrderServer;

import java.util.List;
import java.util.Locale;

/**
 * Tab 2: run the same load test against the same server in both modes and put the two
 * results next to each other.
 */
public final class PerfCompareTab implements DemoTab {

    private static final List<Integer> REQUEST_OPTIONS = List.of(1_000, 5_000, 20_000);
    private static final List<Integer> CONCURRENCY_OPTIONS = List.of(100, 500, 2_000);

    private final OrderServer server = new OrderServer();
    private final LoadGenerator generator = new LoadGenerator();

    private final ModeToggle modeToggle = new ModeToggle();
    private final ComboBox<OrderServer.Endpoint> endpointBox = new ComboBox<>();
    private final ComboBox<Integer> requestsBox = new ComboBox<>();
    private final ComboBox<Integer> concurrencyBox = new ComboBox<>();

    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset stats");

    private final StatsPanel pastPanel = new StatsPanel(Mode.PAST);
    private final StatsPanel futurePanel = new StatsPanel(Mode.FUTURE);
    private final Label comparison = new Label();
    private final Label status = new Label();
    private final Label mismatchWarning = new Label();

    private final XYChart.Series<Number, Number> pastSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> futureSeries = new XYChart.Series<>();
    private final LineChart<Number, Number> chart;

    private final VBox node;

    public PerfCompareTab() {
        chart = buildChart();

        HBox panels = new HBox(12, pastPanel.getNode(), futurePanel.getNode());
        panels.setAlignment(Pos.TOP_CENTER);

        comparison.getStyleClass().add("comparison-line");
        comparison.setMaxWidth(Double.MAX_VALUE);
        comparison.setAlignment(Pos.CENTER);
        comparison.setVisible(false);
        comparison.setManaged(false);

        mismatchWarning.getStyleClass().add("warning-text");
        mismatchWarning.setVisible(false);
        mismatchWarning.setManaged(false);

        VBox chartBox = new VBox(6, chartLegend(), chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        VBox.setVgrow(chartBox, Priority.ALWAYS);
        chart.setMinHeight(200);

        VBox content = new VBox(10, buildControls(), buildCaptions(), mismatchWarning,
                panels, comparison, chartBox);
        content.setPadding(new Insets(12));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPannable(false);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        node = new VBox(scroller);

        runButton.getStyleClass().addAll("run-button", modeToggle.getMode().styleClass());
        modeToggle.modeProperty().addListener((obs, was, is) -> {
            runButton.getStyleClass().setAll("run-button", is.styleClass());
            refreshMismatchWarning();
        });

        setRunning(false);
    }

    // ------------------------------------------------------------------ controls

    private VBox buildControls() {
        endpointBox.getItems().setAll(OrderServer.Endpoint.values());
        endpointBox.getSelectionModel().select(OrderServer.Endpoint.NORMAL);
        endpointBox.setTooltip(new Tooltip(
                "Which handler to hit. Each one just sleeps for its latency and returns JSON."));

        requestsBox.getItems().setAll(REQUEST_OPTIONS);
        requestsBox.getSelectionModel().select(Integer.valueOf(5_000));
        requestsBox.setConverter(thousandsConverter());
        requestsBox.setTooltip(new Tooltip("Total requests to send in the timed run."));

        concurrencyBox.getItems().setAll(CONCURRENCY_OPTIONS);
        concurrencyBox.getSelectionModel().select(Integer.valueOf(2_000));
        concurrencyBox.setConverter(thousandsConverter());
        concurrencyBox.setTooltip(new Tooltip(
                "How many requests are in flight at once.\n"
                        + "The contrast is sharpest when this is well above the server's "
                        + "200-thread pool."));

        Runnable onConfigChange = this::refreshMismatchWarning;
        endpointBox.valueProperty().addListener((o, a, b) -> onConfigChange.run());
        requestsBox.valueProperty().addListener((o, a, b) -> onConfigChange.run());
        concurrencyBox.valueProperty().addListener((o, a, b) -> onConfigChange.run());

        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip("Run this config against the server  (⌘R)"));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Abandon the run  (⌘.)"));

        resetButton.getStyleClass().add("secondary-button");
        resetButton.setOnAction(e -> clearOutput());
        resetButton.setTooltip(new Tooltip("Empty both panels and the chart  (⌘K)"));

        status.getStyleClass().add("elapsed-timer");

        for (Region control : new Region[]{endpointBox, requestsBox, concurrencyBox,
                runButton, stopButton, resetButton}) {
            // Never shrink a control below the width of its own text. Combined with the
            // FlowPane below, a too-narrow window wraps the row instead of turning every
            // label into "...".
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        FlowPane row = new FlowPane(10, 8,
                field("Endpoint", endpointBox),
                field("Requests", requestsBox),
                field("Concurrency", concurrencyBox),
                field("", modeToggle.getNode()),
                field("", runButton),
                field("", stopButton),
                field("", resetButton));
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
                "Each handler is one Thread.sleep(), standing in for a database call.");
        serverNote.getStyleClass().add("caption");
        serverNote.setTooltip(new Tooltip(
                "The handlers are identical in both modes. The only thing that changes is the "
                        + "executor the server runs them on: a fixed pool of 200 platform "
                        + "threads, or one virtual thread per request."));

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
        pastSeries.setName("past");
        futureSeries.setName("present");
        lineChart.getData().add(pastSeries);
        lineChart.getData().add(futureSeries);
        styleSeries(pastSeries, Mode.PAST);
        styleSeries(futureSeries, Mode.FUTURE);
        return lineChart;
    }

    /** JavaFX hands series rotating default-colour classes, so pin the stroke directly. */
    private static void styleSeries(XYChart.Series<Number, Number> series, Mode mode) {
        if (series.getNode() != null) {
            series.getNode().setStyle("-fx-stroke: " + mode.accent() + "; -fx-stroke-width: 2.5;");
        }
    }

    private HBox chartLegend() {
        HBox legend = new HBox(16, legendItem(Mode.PAST), legendItem(Mode.FUTURE));
        legend.setAlignment(Pos.CENTER_LEFT);
        return legend;
    }

    private static Label legendItem(Mode mode) {
        Label label = new Label("●  " + mode.label());
        label.getStyleClass().add("caption");
        label.setStyle("-fx-text-fill: " + mode.accent() + "; -fx-font-weight: bold;");
        return label;
    }

    private static StringConverter<Integer> thousandsConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : String.format(Locale.US, "%,d", value);
            }

            @Override
            public Integer fromString(String text) {
                return Integer.valueOf(text.replace(",", "").trim());
            }
        };
    }

    // ----------------------------------------------------------------- execution

    @Override
    public void runDemo() {
        if (generator.isRunning()) {
            return;
        }
        Mode mode = modeToggle.getMode();
        OrderServer.Endpoint endpoint = endpointBox.getValue();
        int total = requestsBox.getValue();
        int concurrency = concurrencyBox.getValue();

        setRunning(true);
        status.setText("starting server…");
        seriesFor(mode).getData().clear();

        // Server start (and restart on a mode change) is off the FX thread; the generator
        // itself must be kicked off back on it because it drives a Timeline.
        Thread starter = new Thread(() -> {
            try {
                server.startFor(mode);
                int port = server.port();
                Platform.runLater(() ->
                        generator.start(mode, port, endpoint, total, concurrency, listener(mode)));
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

    private LoadGenerator.Listener listener(Mode mode) {
        return new LoadGenerator.Listener() {
            @Override
            public void onSamples(List<LoadGenerator.Sample> batch) {
                XYChart.Series<Number, Number> series = seriesFor(mode);
                for (LoadGenerator.Sample sample : batch) {
                    series.getData().add(
                            new XYChart.Data<>(sample.elapsedSeconds(), sample.latencyMillis()));
                }
                styleSeries(series, mode);
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
                panelFor(mode).setResult(result);
                refreshComparison();
                refreshMismatchWarning();
            }

            @Override
            public void onStopped(int completed) {
                setRunning(false);
                status.setText("");
                seriesFor(mode).getData().clear();
                showWarning(String.format(Locale.US,
                        "Run stopped after %,d requests — %s panel still shows its last "
                                + "complete run.", completed,
                        mode == Mode.PAST ? "the past" : "the present"));
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                status.setText("");
                showWarning(message);
            }
        };
    }

    private StatsPanel panelFor(Mode mode) {
        return mode == Mode.PAST ? pastPanel : futurePanel;
    }

    private XYChart.Series<Number, Number> seriesFor(Mode mode) {
        return mode == Mode.PAST ? pastSeries : futureSeries;
    }

    /** The sentence the audience remembers. Only shown once both sides have run. */
    private void refreshComparison() {
        RunResult past = pastPanel.getResult();
        RunResult future = futurePanel.getResult();
        boolean both = past != null && future != null;
        comparison.setVisible(both);
        comparison.setManaged(both);
        if (!both) {
            return;
        }
        double ratio = past.throughput() <= 0 ? 0 : future.throughput() / past.throughput();
        comparison.setText(String.format(Locale.US,
                "Present: %.1f× throughput,  p99 latency %,d ms → %,d ms",
                ratio, past.p99(), future.p99()));
    }

    /**
     * Inline, never a popup — a modal dialog mid-talk is worse than the mistake it warns
     * about.
     */
    private void refreshMismatchWarning() {
        RunResult past = pastPanel.getResult();
        RunResult future = futurePanel.getResult();
        if (past != null && future != null && !past.sameConfigAs(future)) {
            showWarning("⚠  The two panels were run with different settings, so this "
                    + "comparison is not valid. Re-run one side with matching settings.");
        } else {
            hideWarning();
        }
    }

    private void showWarning(String message) {
        mismatchWarning.setText(message);
        mismatchWarning.setVisible(true);
        mismatchWarning.setManaged(true);
    }

    private void hideWarning() {
        mismatchWarning.setVisible(false);
        mismatchWarning.setManaged(false);
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        resetButton.setDisable(running);
        modeToggle.setDisable(running);
        endpointBox.setDisable(running);
        requestsBox.setDisable(running);
        concurrencyBox.setDisable(running);
    }

    @Override
    public void stopDemo() {
        generator.stop();
    }

    /** ⌘K here means "reset stats" — both panels, the chart and the comparison line. */
    @Override
    public void clearOutput() {
        if (generator.isRunning()) {
            return;
        }
        pastPanel.clear();
        futurePanel.clear();
        pastSeries.getData().clear();
        futureSeries.getData().clear();
        comparison.setVisible(false);
        comparison.setManaged(false);
        hideWarning();
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
