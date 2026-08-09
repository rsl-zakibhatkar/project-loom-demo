package loomdemo.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import loomdemo.exec.Snippet;

import java.util.ArrayList;
import java.util.List;

/**
 * The three-way snippet switch for Threads 101.
 *
 * <p>Structurally this is {@link ModeToggle} widened past two options. It keeps the same
 * three defences, all of which are load-bearing on stage: a suppression flag so
 * programmatic selection cannot re-enter the listener, a null check because re-clicking
 * the selected button in a {@link ToggleGroup} would otherwise deselect everything, and a
 * vetoable {@link Guard} separate from a guard-bypassing {@link #setSnippet} so the tab
 * can ask before discarding live edits.
 */
public final class SnippetPicker {

    @FunctionalInterface
    public interface Guard {
        /** Return false to refuse the change and keep the current snippet. */
        boolean allow(Snippet requested);
    }

    private final List<ToggleButton> buttons = new ArrayList<>();
    private final ToggleGroup group = new ToggleGroup();
    private final ObjectProperty<Snippet> snippet =
            new SimpleObjectProperty<>(Snippet.values()[0]);
    private final HBox node;

    private Guard guard = requested -> true;
    private boolean suppress;

    public SnippetPicker() {
        for (Snippet value : Snippet.values()) {
            ToggleButton button = new ToggleButton(value.label());
            button.getStyleClass().addAll("mode-button", "snippet-button");
            button.setTooltip(new Tooltip(value.caption()));
            button.setToggleGroup(group);
            button.setUserData(value);
            buttons.add(button);
        }
        buttons.get(0).setSelected(true);

        group.selectedToggleProperty().addListener((obs, was, is) -> {
            if (suppress) {
                return;
            }
            if (is == null) {
                // Clicking the already-selected button would otherwise deselect all three.
                syncButtons(snippet.get());
                return;
            }
            Snippet requested = (Snippet) is.getUserData();
            if (requested == snippet.get()) {
                return;
            }
            if (guard.allow(requested)) {
                snippet.set(requested);
            } else {
                syncButtons(snippet.get());
            }
        });

        node = new HBox();
        node.getChildren().addAll(buttons);
        node.getStyleClass().add("mode-toggle");
        node.setAlignment(Pos.CENTER_LEFT);
    }

    private void syncButtons(Snippet target) {
        suppress = true;
        for (Toggle button : buttons) {
            ((ToggleButton) button).setSelected(button.getUserData() == target);
        }
        suppress = false;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    public Snippet getSnippet() {
        return snippet.get();
    }

    /** Change the selection programmatically, bypassing the guard. */
    public void setSnippet(Snippet target) {
        syncButtons(target);
        snippet.set(target);
    }

    public ObjectProperty<Snippet> snippetProperty() {
        return snippet;
    }

    public void setDisable(boolean disable) {
        buttons.forEach(button -> button.setDisable(disable));
    }

    public HBox getNode() {
        return node;
    }
}
