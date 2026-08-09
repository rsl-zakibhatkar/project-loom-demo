package loomdemo.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import loomdemo.Mode;

/**
 * The past/future switch, used by both tabs.
 *
 * <p>A {@link Guard} can veto a change — the Thread Bomb tab uses that to ask before
 * throwing away live edits. Vetoing puts the visual selection back where it was, so the
 * buttons never disagree with {@link #modeProperty()}.
 */
public final class ModeToggle {

    @FunctionalInterface
    public interface Guard {
        /** Return false to refuse the change and keep the current mode. */
        boolean allow(Mode requested);
    }

    private final ToggleButton pastButton = button(Mode.PAST);
    private final ToggleButton futureButton = button(Mode.FUTURE);
    private final ToggleGroup group = new ToggleGroup();
    private final ObjectProperty<Mode> mode = new SimpleObjectProperty<>(Mode.PAST);
    private final HBox node;

    private Guard guard = requested -> true;
    private boolean suppress;

    public ModeToggle() {
        pastButton.setToggleGroup(group);
        futureButton.setToggleGroup(group);
        pastButton.setSelected(true);

        group.selectedToggleProperty().addListener((obs, was, is) -> {
            if (suppress) {
                return;
            }
            if (is == null) {
                // Clicking the already-selected button would otherwise deselect both.
                syncButtons(mode.get());
                return;
            }
            Mode requested = is == pastButton ? Mode.PAST : Mode.FUTURE;
            if (requested == mode.get()) {
                return;
            }
            if (guard.allow(requested)) {
                mode.set(requested);
            } else {
                syncButtons(mode.get());
            }
        });

        node = new HBox(pastButton, futureButton);
        node.getStyleClass().add("mode-toggle");
        node.setAlignment(Pos.CENTER_LEFT);
    }

    private static ToggleButton button(Mode mode) {
        ToggleButton button = new ToggleButton(mode.label());
        button.getStyleClass().addAll("mode-button", mode.styleClass());
        button.setTooltip(new Tooltip(mode.subtitle()));
        return button;
    }

    private void syncButtons(Mode target) {
        suppress = true;
        pastButton.setSelected(target == Mode.PAST);
        futureButton.setSelected(target == Mode.FUTURE);
        suppress = false;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    public Mode getMode() {
        return mode.get();
    }

    /** Change mode programmatically, bypassing the guard. */
    public void setMode(Mode target) {
        syncButtons(target);
        mode.set(target);
    }

    public ObjectProperty<Mode> modeProperty() {
        return mode;
    }

    public void setDisable(boolean disable) {
        pastButton.setDisable(disable);
        futureButton.setDisable(disable);
    }

    public HBox getNode() {
        return node;
    }
}
