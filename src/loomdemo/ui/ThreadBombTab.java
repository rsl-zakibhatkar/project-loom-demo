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

    /**
     * The same flags in both modes, so the {@code $ java …} line the console prints is
     * byte-identical across the two runs — the demo's claim is that only one word of the
     * program changed, and a different command line would undercut it. The past side still
     * dies on the OS thread limit rather than on heap, so the larger heap costs it
     * nothing, and {@code -Xss1m} is simply ignored by virtual threads, which is a point
     * worth making out loud.
     */
    private static final List<String> FLAGS = List.of("-Xmx2g", "-Xss1m");

    private static final Pattern DIED =
            Pattern.compile("Died at thread #([\\d,_]+) of ([\\d,_]+)");
    private static final Pattern ALIVE =
            Pattern.compile("All ([\\d,_]+) threads alive");

    private final JavaCodeArea editor = new JavaCodeArea();
    private final ConsolePane console = new ConsolePane();
    private final ChildJvmRunner runner = new ChildJvmRunner();
    private final Scoreboard scoreboard;

    private final ModeToggle modeToggle = new ModeToggle();
    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Label dirtyBadge = new Label("edited");
    private final HBox confirmBar;
    private final VBox node;

    private Mode mode = Mode.PAST;
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

    private FlowPane buildControls() {
        // Switching modes replaces the editor contents, so ask first if they have been
        // touched. Returning false leaves the toggle showing the current mode.
        modeToggle.setGuard(requested -> {
            if (editor.isDirty()) {
                showConfirm(requested);
                return false;
            }
            return true;
        });
        modeToggle.modeProperty().addListener((obs, was, is) -> switchTo(is));

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
        caption.setWrapText(true);
        caption.setMaxWidth(460);

        runButton.setMinWidth(Region.USE_PREF_SIZE);
        stopButton.setMinWidth(Region.USE_PREF_SIZE);

        // FlowPane rather than HBox: at 130% presentation fonts these controls no longer
        // fit on one line, and wrapping beats truncating "Take me to the past" to "...".
        FlowPane bar = new FlowPane(12, 8, modeToggle.getNode(), runButton, stopButton, caption);
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
            modeToggle.setMode(requested);   // fires the listener, which swaps the source
        });
        confirmBar.setVisible(true);
        confirmBar.setManaged(true);
    }

    private void hideConfirm() {
        confirmBar.setVisible(false);
        confirmBar.setManaged(false);
    }

    private void switchTo(Mode target) {
        mode = target;
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

        console.appendLine("$ java " + String.join(" ", FLAGS) + " Demo.java");
        console.appendLine("  (child JVM: " + ChildJvmRunner.javaBinary() + ")");
        console.appendLine("");

        setRunning(true);
        runner.start(editor.getSource(), FLAGS, new ChildJvmRunner.Listener() {
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
                // The denominator belongs on the banner — it is what makes this number
                // read against the present side's million instead of floating alone. The
                // scoreboard chip stays short; it sits in the title bar beside a chip that
                // already says a million.
                console.setPunchline("Died at thread #" + died.group(1)
                        + " of " + died.group(2), Mode.PAST);
                scoreboard.setResult(Mode.PAST, "died at #" + died.group(1));
                punchlineShown = true;
                return;
            }
            Matcher alive = ALIVE.matcher(line);
            if (alive.find()) {
                String count = alive.group(1);
                console.setPunchline("All " + count + " threads alive", Mode.FUTURE);
                scoreboard.setResult(Mode.FUTURE, count + " alive");
                punchlineShown = true;
                return;
            }
        }
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        modeToggle.setDisable(running);
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
