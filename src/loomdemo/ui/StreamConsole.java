package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

/**
 * Output console where individual lines can be coloured.
 *
 * <p>Threads 101 needs to tint {@code cook-1} and {@code cook-2} differently so the
 * interleaving is legible from the back of the room, and a plain {@code TextArea} has one
 * text fill for its entire contents. This is the same console in every other respect —
 * monospaced, projector-sized, auto-scrolling, em-based fonts that compose with
 * presentation mode — built on a RichTextFX {@code StyleClassedTextArea} instead.
 *
 * <p>{@link ConsolePane} is deliberately left alone. It carries the Thread Bomb demo,
 * which streams orders of magnitude more output and needs the punchline banner this class
 * has no use for. Two consoles is the cheaper trade than one console that has to be both.
 *
 * <p>Unlike {@link ConsolePane}, this one appends a line at a time rather than a batch at
 * a time, because each line carries its own style. That is fine for snippets that print
 * tens of lines; it would not be for a thread bomb.
 */
public final class StreamConsole {

    /** 1.07em over the 15px root ≈ 16px: the floor the brief asks for. */
    private static final double MIN_EM = 1.07;
    private static final double MAX_EM = 3.0;
    private static final double STEP_EM = 0.12;
    private static final double DEFAULT_EM = 1.15;

    /** This console accumulates runs, so the ceiling matters more here than elsewhere. */
    private static final int MAX_CHARS = 200_000;
    private static final int TRIM_TO = 150_000;

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final VirtualizedScrollPane<StyleClassedTextArea> scrollPane;
    private final Label elapsed = new Label("");
    private final Label sizeLabel = new Label();
    private final VBox node;

    private double fontEm = DEFAULT_EM;
    private Runnable clearAction = this::clear;

    public StreamConsole() {
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("stream-console");
        area.setFocusTraversable(false);

        scrollPane = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        elapsed.getStyleClass().add("elapsed-timer");

        Label heading = new Label("Output");
        heading.getStyleClass().add("section-label");

        Button smaller = iconButton("A−",
                "Smaller console font  (font scales with Presentation mode too)");
        smaller.setOnAction(e -> adjustFont(-STEP_EM));
        Button bigger = iconButton("A+",
                "Larger console font  (font scales with Presentation mode too)");
        bigger.setOnAction(e -> adjustFont(+STEP_EM));

        sizeLabel.getStyleClass().add("caption");

        Button clear = new Button("Clear");
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> clearAction.run());
        clear.setTooltip(new Tooltip("Clear the console and reset the run counter  (⌘K)"));

        HBox toolbar = new HBox(heading, spacer(), elapsed, smaller, sizeLabel, bigger, clear);
        toolbar.getStyleClass().add("console-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        node = new VBox(toolbar, scrollPane);
        node.getStyleClass().add("console-pane");

        applyFont();
    }

    /**
     * What the Clear button does. The tab overrides this so clearing also resets the run
     * counter and the thread-to-colour assignments.
     */
    public void setClearAction(Runnable action) {
        this.clearAction = action;
    }

    /** Append one line. {@code styleClass} may be null for ordinary output. */
    public void appendLine(String text, String styleClass) {
        if (styleClass == null) {
            area.append(text + "\n", "");
        } else {
            area.append(text + "\n", styleClass);
        }
        trimIfHuge();
        scrollToBottom();
    }

    public void appendLine(String text) {
        appendLine(text, null);
    }

    /** The {@code ──── Run #3 ────} rule that separates consecutive runs. */
    public void appendDivider(String text) {
        appendLine(text, "run-divider");
    }

    public void clear() {
        area.replaceText("");
        elapsed.setText("");
    }

    public void setElapsedText(String text) {
        elapsed.setText(text);
    }

    private void scrollToBottom() {
        int last = area.getParagraphs().size() - 1;
        if (last >= 0) {
            area.showParagraphAtBottom(last);
        }
    }

    private void trimIfHuge() {
        int length = area.getLength();
        if (length > MAX_CHARS) {
            area.replaceText(0, length - TRIM_TO, "");
        }
    }

    private void adjustFont(double delta) {
        fontEm = Math.max(MIN_EM, Math.min(MAX_EM, fontEm + delta));
        applyFont();
    }

    private void applyFont() {
        area.setStyle("-fx-font-size: " + String.format("%.3f", fontEm) + "em;");
        sizeLabel.setText(Math.round(fontEm * 15) + "pt");
    }

    private static Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public VBox getNode() {
        return node;
    }
}
