package loomdemo.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import loomdemo.Mode;

/**
 * The running score, parked in the title bar so it survives tab switches:
 *
 * <pre>Past: died at #8,241   |   Future: 1,000,000 ✓</pre>
 *
 * <p>Each side keeps the most recent result for that mode, so the contrast stays on
 * screen for the rest of the talk.
 */
public final class Scoreboard {

    private static final String EMPTY = "—";

    private final Label pastValue = new Label(EMPTY);
    private final Label futureValue = new Label(EMPTY);
    private final HBox node;

    public Scoreboard() {
        HBox past = chip(Mode.PAST, "PAST", pastValue);
        HBox future = chip(Mode.FUTURE, "FUTURE", futureValue);

        Label divider = new Label("|");
        divider.getStyleClass().add("score-divider");

        node = new HBox(past, divider, future);
        node.getStyleClass().add("scoreboard");
        node.setAlignment(Pos.CENTER);
        Tooltip.install(node, new Tooltip(
                "Most recent Thread Bomb result from each mode. Survives tab switches."));
    }

    private HBox chip(Mode mode, String tagText, Label value) {
        Label tag = new Label(tagText);
        tag.getStyleClass().addAll("score-tag", mode.styleClass());

        value.getStyleClass().addAll("score-value", mode.styleClass(), "empty");

        HBox chip = new HBox(tag, value);
        chip.getStyleClass().add("score-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    public void setResult(Mode mode, String text) {
        Label target = mode == Mode.PAST ? pastValue : futureValue;
        target.setText(text);
        target.getStyleClass().remove("empty");
    }

    public void clear(Mode mode) {
        Label target = mode == Mode.PAST ? pastValue : futureValue;
        target.setText(EMPTY);
        if (!target.getStyleClass().contains("empty")) {
            target.getStyleClass().add("empty");
        }
    }

    public HBox getNode() {
        return node;
    }
}
