package loomdemo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.DemoTab;
import loomdemo.Mode;
import loomdemo.exec.ChildJvmRunner;
import loomdemo.exec.DemoSources;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tab 1: an editable Java program on the left, the child JVM's live output on the right.
 */
public final class ThreadBombTab implements DemoTab {

    private static final List<String> PAST_FLAGS = List.of("-Xmx512m", "-Xss1m");
    private static final List<String> FUTURE_FLAGS = List.of("-Xmx2g");

    private static final Pattern DIED = Pattern.compile("Died at thread #([\\d,_]+)");
    private static final Pattern COMPLETED =
            Pattern.compile("Completed ([\\d,_]+) tasks in ([\\d.]+)s");

    private final JavaCodeArea editor = new JavaCodeArea();
    private final ConsolePane console = new ConsolePane();
    private final ChildJvmRunner runner = new ChildJvmRunner();
    private final Scoreboard scoreboard;

    private final ToggleButton pastButton = modeButton(Mode.PAST);
    private final ToggleButton futureButton = modeButton(Mode.FUTURE);
    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Label dirtyBadge = new Label("edited");
    private final HBox confirmBar;
    private final VBox node;

    private Mode mode = Mode.PAST;
    private boolean suppressToggle;
    private boolean punchlineShown;

    public ThreadBombTab(Scoreboard scoreboard) {
        this.scoreboard = scoreboard;

        confirmBar = buildConfirmBar();
        VBox editorFrame = buildEditorFrame();

        SplitPane split = new SplitPane(editorFrame, console.getNode());
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        node = new VBox(buildControls(), confirmBar, split);
        node.setSpacing(10);
        node.setPadding(new Insets(12));

        editor.loadSource(DemoSources.forMode(mode));
        updateModeStyling();
        setRunning(false);
    }

    // ------------------------------------------------------------------ controls

    private HBox buildControls() {
        ToggleGroup group = new ToggleGroup();
        pastButton.setToggleGroup(group);
        futureButton.setToggleGroup(group);
        pastButton.setSelected(true);

        // A ToggleGroup flips selection before we get a say, so when there are unsaved
        // edits we put the selection back and ask first.
        group.selectedToggleProperty().addListener((obs, was, is) -> {
            if (suppressToggle) {
                return;
            }
            if (is == null) {
                suppressToggle = true;
                group.selectToggle(was);
                suppressToggle = false;
                return;
            }
            Mode requested = is == pastButton ? Mode.PAST : Mode.FUTURE;
            if (requested == mode) {
                return;
            }
            if (editor.isDirty()) {
                selectToggleFor(mode);
                showConfirm(requested);
            } else {
                switchTo(requested);
            }
        });

        HBox toggle = new HBox(pastButton, futureButton);
        toggle.getStyleClass().add("mode-toggle");
        toggle.setAlignment(Pos.CENTER_LEFT);

        runButton.getStyleClass().addAll("run-button", mode.styleClass());
        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip("Run this code in a separate JVM  (⌘R)"));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Kill the child JVM  (⌘.)"));

        Label caption = new Label(
                "Runs in a child JVM, never in this app — that is why the bomb can go off "
                        + "without taking the window with it.");
        caption.getStyleClass().add("caption");
        caption.setMaxWidth(460);

        HBox bar = new HBox(12, toggle, runButton, stopButton, spacer(), caption);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static ToggleButton modeButton(Mode mode) {
        ToggleButton button = new ToggleButton(mode.label());
        button.getStyleClass().addAll("mode-button", mode.styleClass());
        button.setTooltip(new Tooltip(mode.subtitle()));
        return button;
    }

    private VBox buildEditorFrame() {
        Label heading = new Label("Demo.java");
        heading.getStyleClass().add("section-label");

        dirtyBadge.getStyleClass().add("dirty-badge");
        dirtyBadge.visibleProperty().bind(editor.dirtyProperty());
        dirtyBadge.managedProperty().bind(editor.dirtyProperty());

        Button smaller = new Button("A−");
        smaller.getStyleClass().add("icon-button");
        smaller.setFocusTraversable(false);
        smaller.setOnAction(e -> editor.adjustFont(-1));

        Button bigger = new Button("A+");
        bigger.getStyleClass().add("icon-button");
        bigger.setFocusTraversable(false);
        bigger.setOnAction(e -> editor.adjustFont(+1));

        Button reset = new Button("Reset");
        reset.getStyleClass().add("secondary-button");
        reset.setTooltip(new Tooltip("Restore the original source for this mode"));
        reset.setOnAction(e -> {
            editor.loadSource(DemoSources.forMode(mode));
            hideConfirm();
        });

        HBox toolbar = new HBox(heading, dirtyBadge, spacer(), smaller, bigger, reset);
        toolbar.getStyleClass().add("editor-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Region area = editor.getNode();
        VBox.setVgrow(area, Priority.ALWAYS);

        VBox frame = new VBox(toolbar, area);
        frame.getStyleClass().add("editor-frame");
        return frame;
    }

    private HBox buildConfirmBar() {
        Label message = new Label();
        message.getStyleClass().add("message");

        Button discard = new Button("Discard my edits");
        discard.getStyleClass().add("secondary-button");

        Button keep = new Button("Keep editing");
        keep.getStyleClass().add("secondary-button");
        keep.setOnAction(e -> hideConfirm());

        HBox bar = new HBox(12, message, spacer(), discard, keep);
        bar.getStyleClass().add("inline-confirm");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);
        bar.setUserData(new Object[]{message, discard});
        return bar;
    }

    private void showConfirm(Mode requested) {
        Object[] parts = (Object[]) confirmBar.getUserData();
        Label message = (Label) parts[0];
        Button discard = (Button) parts[1];
        message.setText("You have edited this code. Switching to \"" + requested.label()
                + "\" will replace it.");
        discard.setOnAction(e -> {
            hideConfirm();
            switchTo(requested);
        });
        confirmBar.setVisible(true);
        confirmBar.setManaged(true);
    }

    private void hideConfirm() {
        confirmBar.setVisible(false);
        confirmBar.setManaged(false);
    }

    private void selectToggleFor(Mode target) {
        suppressToggle = true;
        pastButton.setSelected(target == Mode.PAST);
        futureButton.setSelected(target == Mode.FUTURE);
        suppressToggle = false;
    }

    private void switchTo(Mode target) {
        mode = target;
        selectToggleFor(target);
        editor.loadSource(DemoSources.forMode(target));
        console.clearPunchline();
        updateModeStyling();
    }

    private void updateModeStyling() {
        runButton.getStyleClass().setAll("run-button", mode.styleClass());
    }

    // ----------------------------------------------------------------- execution

    @Override
    public void runDemo() {
        if (runner.isRunning()) {
            return;
        }
        hideConfirm();
        punchlineShown = false;
        console.clear();

        List<String> flags = mode == Mode.PAST ? PAST_FLAGS : FUTURE_FLAGS;
        console.appendLine("$ java " + String.join(" ", flags) + " Demo.java");
        console.appendLine("  (child JVM: " + ChildJvmRunner.javaBinary() + ")");
        console.appendLine("");

        setRunning(true);
        runner.start(editor.getSource(), flags, new ChildJvmRunner.Listener() {
            @Override
            public void onLines(List<String> lines) {
                console.appendLines(lines);
                if (!punchlineShown) {
                    scanForPunchline(lines);
                }
            }

            @Override
            public void onTick(long elapsedMillis) {
                console.setElapsedText("⏱  " + ChildJvmRunner.formatElapsed(elapsedMillis));
            }

            @Override
            public void onExit(int exitCode, boolean stoppedByUser, long elapsedMillis) {
                setRunning(false);
                console.setElapsedText("⏱  " + ChildJvmRunner.formatElapsed(elapsedMillis));
                console.appendLine("");
                console.appendLine(stoppedByUser
                        ? "[stopped after " + ChildJvmRunner.formatElapsed(elapsedMillis) + "]"
                        : "[child JVM exited with code " + exitCode + " after "
                                + ChildJvmRunner.formatElapsed(elapsedMillis) + "]");
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                console.appendLine("");
                console.appendLine("ERROR: " + message);
            }
        });
    }

    private void scanForPunchline(List<String> lines) {
        for (String line : lines) {
            Matcher died = DIED.matcher(line);
            if (died.find()) {
                console.setPunchline("Died at thread #" + died.group(1), Mode.PAST);
                scoreboard.setResult(Mode.PAST, "died at #" + died.group(1));
                punchlineShown = true;
                return;
            }
            Matcher completed = COMPLETED.matcher(line);
            if (completed.find()) {
                String count = completed.group(1);
                String seconds = completed.group(2);
                console.setPunchline("Completed " + count + " tasks in " + seconds + "s",
                        Mode.FUTURE);
                scoreboard.setResult(Mode.FUTURE, count + " ✓  in " + seconds + "s");
                punchlineShown = true;
                return;
            }
        }
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        pastButton.setDisable(running);
        futureButton.setDisable(running);
        editor.setEditable(!running);
    }

    @Override
    public void stopDemo() {
        runner.stop();
    }

    @Override
    public void clearOutput() {
        console.clear();
    }

    @Override
    public void shutdown() {
        runner.stop();
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
