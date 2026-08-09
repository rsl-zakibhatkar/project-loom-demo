package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.Mode;

import java.util.List;

/**
 * Output console: monospaced, projector-sized, auto-scrolling, with the demo's payoff
 * line pinned underneath in huge mode-coloured text.
 *
 * <p>The punchline gets its own banner rather than living in the scroll buffer, because
 * the one line the audience needs to read is exactly the line that would otherwise
 * scroll away a second after it appears.
 *
 * <p>Font size is expressed in {@code em}, so the presenter's +/- adjustment and the
 * global presentation-mode toggle compose instead of fighting each other.
 */
public final class ConsolePane {

    /** 1.07em over the 15px root ≈ 16px: the floor the brief asks for. */
    private static final double MIN_EM = 1.07;
    private static final double MAX_EM = 3.0;
    private static final double STEP_EM = 0.12;
    private static final double DEFAULT_EM = 1.15;

    /** Runaway output must not turn the console into a memory leak. */
    private static final int MAX_CHARS = 200_000;
    private static final int TRIM_TO = 150_000;

    private final TextArea textArea = new TextArea();
    private final Label punchline = new Label();
    private final Label elapsed = new Label("");
    private final Label sizeLabel = new Label();
    private final VBox node;

    private double fontEm = DEFAULT_EM;

    public ConsolePane() {
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("console-text");
        textArea.setFocusTraversable(false);
        VBox.setVgrow(textArea, Priority.ALWAYS);

        punchline.setVisible(false);
        punchline.setManaged(false);
        punchline.setMaxWidth(Double.MAX_VALUE);

        elapsed.getStyleClass().add("elapsed-timer");

        Label heading = new Label("Output");
        heading.getStyleClass().add("section-label");

        Button smaller = iconButton("A−", "Smaller console font  (font scales with Presentation mode too)");
        smaller.setOnAction(e -> adjustFont(-STEP_EM));
        Button bigger = iconButton("A+", "Larger console font  (font scales with Presentation mode too)");
        bigger.setOnAction(e -> adjustFont(+STEP_EM));

        sizeLabel.getStyleClass().add("caption");

        Button clear = new Button("Clear");
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> clear());
        clear.setTooltip(new Tooltip("Clear the console and the punchline  (⌘K)"));

        HBox toolbar = new HBox(heading, spacer(), elapsed, smaller, sizeLabel, bigger, clear);
        toolbar.getStyleClass().add("console-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        node = new VBox(toolbar, textArea, punchline);
        node.getStyleClass().add("console-pane");

        applyFont();
    }

    private static Button iconButton(String text, String tip) {
        Button button = new Button(text);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        return button;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void adjustFont(double delta) {
        fontEm = Math.max(MIN_EM, Math.min(MAX_EM, fontEm + delta));
        applyFont();
    }

    private void applyFont() {
        textArea.setStyle("-fx-font-size: " + String.format("%.3f", fontEm) + "em;");
        sizeLabel.setText(Math.round(fontEm * 15) + "pt");
    }

    /** Append a batch of already-collected lines. Must be called on the FX thread. */
    public void appendLines(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        textArea.appendText(sb.toString());
        trimIfHuge();
        textArea.setScrollTop(Double.MAX_VALUE);
    }

    public void appendLine(String line) {
        appendLines(List.of(line));
    }

    private void trimIfHuge() {
        int length = textArea.getLength();
        if (length > MAX_CHARS) {
            textArea.deleteText(0, length - TRIM_TO);
        }
    }

    /** The big coloured payoff line. */
    public void setPunchline(String text, Mode mode) {
        punchline.getStyleClass().setAll("punchline", mode.styleClass());
        punchline.setText(text);
        punchline.setVisible(true);
        punchline.setManaged(true);
    }

    public void clearPunchline() {
        punchline.setVisible(false);
        punchline.setManaged(false);
        punchline.setText("");
    }

    public void clear() {
        textArea.clear();
        clearPunchline();
        elapsed.setText("");
    }

    public void setElapsedText(String text) {
        elapsed.setText(text);
    }

    public VBox getNode() {
        return node;
    }
}
