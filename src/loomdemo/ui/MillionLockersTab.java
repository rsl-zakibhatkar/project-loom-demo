package loomdemo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import loomdemo.DemoTab;
import loomdemo.exec.ChildJvmRunner;
import loomdemo.exec.LockerSnippet;

import java.util.List;

/**
 * Tab 5: a million lockers — {@code ThreadLocal} vs {@code ScopedValue} (talk slide 40).
 *
 * <p>Structurally this is {@link ThreadPerRequestTab} — a {@link SegmentedPicker} over a small
 * snippet enum, an editable {@link JavaCodeArea}, and a {@link StreamConsole} that keeps runs
 * stacked under {@code ──── Run #N ────} dividers so the two measured numbers sit on screen
 * together — with two deliberate differences:
 *
 * <ul>
 *   <li><strong>No precompile.</strong> {@code ScopedValue} is a preview API on the bundled
 *       JDK 21, and {@link loomdemo.exec.SourceCompiler} does not enable preview, so it would
 *       reject the snippet. Every run goes straight through the source launcher with
 *       {@link #JVM_ARGS}. The ~250&nbsp;ms compile is invisible next to a run that serves ten
 *       thousand requests.</li>
 *   <li><strong>Preview JVM args.</strong> {@code --enable-preview --source 21} to run the
 *       preview {@code ScopedValue} API, plus a modest {@code -Xmx} with room for the leaked
 *       contexts (the {@code ThreadLocal} snippet strands a few hundred MB on purpose).</li>
 * </ul>
 *
 * <p>Only {@code main} prints, so unlike the other snippet tabs there is no per-thread colouring.
 */
public final class MillionLockersTab implements DemoTab {

    /**
     * Source-launcher flags for every run. {@code --source 21} + {@code --enable-preview} run
     * the {@code ScopedValue} preview API; {@code -Xmx2g} leaves comfortable room for the few
     * hundred MB the {@code ThreadLocal} snippet strands on its idle pool.
     */
    private static final List<String> JVM_ARGS =
            List.of("-Xmx2g", "--enable-preview", "--source", "21");

    private final JavaCodeArea editor = new JavaCodeArea();
    private final StreamConsole console = new StreamConsole();
    private final ChildJvmRunner runner = new ChildJvmRunner();

    private final SegmentedPicker<LockerSnippet> picker = new SegmentedPicker<>(
            List.of(LockerSnippet.values()),
            LockerSnippet::label,
            LockerSnippet.values()[0]);
    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Label caption = new Label();
    private final Label dirtyBadge = new Label("edited");
    private final HBox confirmBar;
    private final VBox node;

    private LockerSnippet snippet = LockerSnippet.values()[0];
    private int runCount;

    /** Guards against a programmatic {@code picker.setValue} re-entering its own listener. */
    private boolean suppressPickerSync;

    public MillionLockersTab() {
        confirmBar = buildConfirmBar();
        VBox editorFrame = buildEditorFrame();

        SplitPane split = new SplitPane(editorFrame, console.getNode());
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        caption.getStyleClass().add("snippet-caption");
        caption.setMaxWidth(Double.MAX_VALUE);

        node = new VBox(buildControls(), confirmBar, caption, split);
        node.setSpacing(10);
        node.setPadding(new Insets(12));

        console.setClearAction(this::clearOutput);

        loadSnippet(snippet);
        setRunning(false);
    }

    private FlowPane buildControls() {
        // SegmentedPicker has no vetoable guard, so the "discard my edits?" question is asked
        // here: on a dirty buffer, revert the picker and show the confirm bar instead.
        picker.valueProperty().addListener((obs, was, is) -> {
            if (suppressPickerSync) {
                return;
            }
            if (editor.isDirty()) {
                suppressPickerSync = true;
                picker.setValue(was);
                suppressPickerSync = false;
                showConfirm(is);
                return;
            }
            switchTo(is);
        });

        runButton.getStyleClass().add("run-button");
        runButton.setOnAction(e -> runDemo());
        runButton.setTooltip(new Tooltip(
                "Run this code in a separate JVM  (⌘R)\nServes 10,000 requests through a reused pool."));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Kill the child JVM  (⌘.)"));

        Label note = new Label("Runs stack up in the console so you can compare the two numbers.");
        note.getStyleClass().add("caption");
        note.setWrapText(true);
        note.setMaxWidth(360);

        runButton.setMinWidth(Region.USE_PREF_SIZE);
        stopButton.setMinWidth(Region.USE_PREF_SIZE);

        FlowPane bar = new FlowPane(12, 8, picker.getNode(), runButton, stopButton, note);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
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
        reset.setTooltip(new Tooltip("Restore the original source for this snippet"));
        reset.setOnAction(e -> {
            loadSnippet(snippet);
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

    private void showConfirm(LockerSnippet requested) {
        Object[] parts = (Object[]) confirmBar.getUserData();
        Label message = (Label) parts[0];
        Button discard = (Button) parts[1];
        message.setText("You have edited this code. Switching to \"" + requested.label()
                + "\" will replace it.");
        discard.setOnAction(e -> {
            hideConfirm();
            suppressPickerSync = true;
            picker.setValue(requested);   // move the buttons without re-asking
            suppressPickerSync = false;
            switchTo(requested);
        });
        confirmBar.setVisible(true);
        confirmBar.setManaged(true);
    }

    private void hideConfirm() {
        confirmBar.setVisible(false);
        confirmBar.setManaged(false);
    }

    private void switchTo(LockerSnippet target) {
        snippet = target;
        loadSnippet(target);
    }

    private void loadSnippet(LockerSnippet target) {
        editor.loadSource(target.source());
        caption.setText(target.caption());
    }

    @Override
    public void runDemo() {
        if (runner.isRunning()) {
            return;
        }
        hideConfirm();

        runCount++;
        if (runCount > 1) {
            console.appendLine("");
        }
        console.appendDivider("──── Run #" + runCount + " ────");

        setRunning(true);
        ChildJvmRunner.Listener listener = new ChildJvmRunner.Listener() {
            @Override
            public void onLines(List<String> lines) {
                for (String line : lines) {
                    console.appendLine(line);
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
                if (stoppedByUser) {
                    console.appendLine("[stopped after "
                            + ChildJvmRunner.formatElapsed(elapsedMillis) + "]", "system-line");
                } else if (exitCode != 0) {
                    console.appendLine("[exited with code " + exitCode + "]", "system-line");
                }
            }

            @Override
            public void onError(String message) {
                setRunning(false);
                console.appendLine("ERROR: " + message, "system-line");
            }
        };

        // Always the source launcher: ScopedValue is a preview API here, so there is no
        // precompiled fast path to take. The launcher also prints the real javac/preview
        // error when the presenter has broken the code.
        runner.start(editor.getSource(), JVM_ARGS, listener);
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        picker.setDisable(running);
        editor.setEditable(!running);
    }

    @Override
    public void stopDemo() {
        runner.stop();
    }

    @Override
    public void clearOutput() {
        console.clear();
        runCount = 0;
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
