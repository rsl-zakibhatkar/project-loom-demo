package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.Mode;
import loomdemo.load.RunResult;

import java.util.Locale;

/**
 * One mode's most recent load-test result. Starts empty with dashes; a run replaces this
 * side only, so past and future can be filled minutes apart and still sit side by side.
 */
public final class StatsPanel {

    private static final String EMPTY = "—";

    private final Mode mode;
    private final Label throughput = new Label(EMPTY);
    private final Label p50 = new Label(EMPTY);
    private final Label p95 = new Label(EMPTY);
    private final Label p99 = new Label(EMPTY);
    private final Label elapsed = new Label(EMPTY);
    private final Label errors = new Label(EMPTY);
    private final Label config = new Label("no run yet");
    private final VBox node;

    private RunResult result;

    public StatsPanel(Mode mode) {
        this.mode = mode;

        Label header = new Label(mode == Mode.PAST
                ? "PAST  ·  platform threads, pool of 200"
                : "FUTURE  ·  virtual threads");
        header.getStyleClass().addAll("panel-header", mode.styleClass());
        header.setMaxWidth(Double.MAX_VALUE);

        throughput.getStyleClass().addAll("hero-number", mode.styleClass(), "empty");
        Label heroUnit = new Label("requests / sec");
        heroUnit.getStyleClass().add("hero-unit");

        VBox hero = new VBox(throughput, heroUnit);
        hero.setAlignment(Pos.CENTER_LEFT);

        HBox percentiles = new HBox(
                statCell("p50", p50), statCell("p95", p95), statCell("p99", p99));
        percentiles.setSpacing(6);

        HBox totals = new HBox(statCell("total time", elapsed), statCell("errors", errors));
        totals.setSpacing(6);

        Tooltip.install(errors, new Tooltip("Failed or non-200 responses during the timed run."));

        config.getStyleClass().add("config-text");
        config.setMaxWidth(Double.MAX_VALUE);
        config.setWrapText(true);

        VBox body = new VBox(hero, separator(), percentiles, separator(), totals, config);
        body.getStyleClass().add("panel-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        node = new VBox(header, body);
        node.getStyleClass().addAll("stats-panel", mode.styleClass());
        HBox.setHgrow(node, Priority.ALWAYS);
    }

    private static VBox statCell(String labelText, Label value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("stat-label");
        value.getStyleClass().addAll("stat-value", "empty");
        VBox cell = new VBox(label, value);
        cell.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cell, Priority.ALWAYS);
        return cell;
    }

    private static Region separator() {
        Region region = new Region();
        region.getStyleClass().add("panel-separator");
        return region;
    }

    public void setResult(RunResult result) {
        this.result = result;
        set(throughput, String.format(Locale.US, "%,.0f", result.throughput()));
        set(p50, result.p50() + " ms");
        set(p95, result.p95() + " ms");
        set(p99, result.p99() + " ms");
        set(elapsed, String.format(Locale.US, "%.2f s", result.elapsedMillis() / 1000.0));
        set(errors, String.format(Locale.US, "%,d", result.errors()));
        // Red only when there is something to be red about.
        errors.getStyleClass().remove("error-count");
        if (result.errors() > 0) {
            errors.getStyleClass().add("error-count");
        }
        config.setText(result.configSummary());

        Tooltip.install(errors, new Tooltip(result.errors() == 0
                ? "No failures. Every request got a 200."
                : result.errorDetail()));
    }

    private static void set(Label label, String text) {
        label.setText(text);
        label.getStyleClass().remove("empty");
    }

    public void clear() {
        result = null;
        for (Label label : new Label[]{throughput, p50, p95, p99, elapsed, errors}) {
            label.setText(EMPTY);
            label.getStyleClass().remove("error-count");
            if (!label.getStyleClass().contains("empty")) {
                label.getStyleClass().add("empty");
            }
        }
        config.setText("no run yet");
    }

    public RunResult getResult() {
        return result;
    }

    public boolean hasResult() {
        return result != null;
    }

    public Mode mode() {
        return mode;
    }

    public VBox getNode() {
        return node;
    }
}
