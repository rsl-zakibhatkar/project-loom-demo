package loomdemo.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A row of buttons standing in for a dropdown over a short, fixed list of options.
 *
 * <p>This exists because {@code ComboBox} popups are a separate native window, and on macOS
 * that window intermittently paints blank until some later event forces a repaint — you
 * click, get an empty rectangle, and only see the options once you move the mouse over
 * them. The list itself is always correct; it is the window that is not drawn. Nothing in
 * application CSS reaches that, so the fix is to have no popup: every option is a button
 * that is always on screen and always one click away.
 *
 * <p>Structurally this is {@link SnippetPicker} with the snippet-specific parts pulled out.
 * It keeps the two defences that matter: a suppression flag so programmatic selection
 * cannot re-enter the listener, and a null check, because re-clicking the selected button
 * in a {@link ToggleGroup} would otherwise deselect everything and leave the demo with no
 * value at all. It has no vetoable guard — unlike a snippet switch, changing a number here
 * discards nothing.
 *
 * <p>Options are compared with {@code equals}, not {@code ==}: the callers pass boxed
 * {@code Integer}s well outside the {@code -128..127} cache, so identity comparison would
 * silently fail to match.
 */
public final class SegmentedPicker<T> {

    private final List<ToggleButton> buttons = new ArrayList<>();
    private final ToggleGroup group = new ToggleGroup();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final HBox node;

    private boolean suppress;

    public SegmentedPicker(List<T> options, Function<T, String> labeller, T initial) {
        for (T option : options) {
            ToggleButton button = new ToggleButton(labeller.apply(option));
            button.getStyleClass().addAll("mode-button", "segmented-button");
            button.setToggleGroup(group);
            button.setUserData(option);
            // Never shrink below the label's own width. A "20,000" truncated to "2..." is
            // not a cosmetic problem when it is the setting the audience is being told.
            button.setMinWidth(Region.USE_PREF_SIZE);
            buttons.add(button);
        }

        value.set(initial);
        syncButtons(initial);

        group.selectedToggleProperty().addListener((obs, was, is) -> {
            if (suppress) {
                return;
            }
            if (is == null) {
                // Clicking the already-selected button would otherwise deselect the row.
                syncButtons(value.get());
                return;
            }
            @SuppressWarnings("unchecked")
            T picked = (T) is.getUserData();
            value.set(picked);
        });

        node = new HBox();
        node.getChildren().addAll(buttons);
        node.getStyleClass().add("mode-toggle");
        node.setAlignment(Pos.CENTER_LEFT);
    }

    private void syncButtons(T target) {
        suppress = true;
        for (ToggleButton button : buttons) {
            button.setSelected(Objects.equals(button.getUserData(), target));
        }
        suppress = false;
    }

    public T getValue() {
        return value.get();
    }

    public void setValue(T target) {
        syncButtons(target);
        value.set(target);
    }

    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public void setDisable(boolean disable) {
        buttons.forEach(button -> button.setDisable(disable));
    }

    public HBox getNode() {
        return node;
    }
}
