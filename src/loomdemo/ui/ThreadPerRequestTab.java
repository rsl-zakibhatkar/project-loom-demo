package loomdemo.ui;

import javafx.animation.PauseTransition;
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
import javafx.util.Duration;
import loomdemo.DemoTab;
import loomdemo.exec.ChildJvmRunner;
import loomdemo.exec.SourceCompiler;
import loomdemo.exec.ThreadPerRequestSnippet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tab 3: thread-per-request was the good design (talk slide 14).
 *
 * <p>Structurally this is {@link Threads101Tab} over a different, shorter snippet set: two
 * runnable programs the presenter switches between, compiled ahead of time and run in a
 * child JVM, with output coloured by the thread that printed it. The two snippets split
 * slide 14's four virtues two apiece — {@link ThreadPerRequestSnippet#HANDLER} carries
 * sequential code and ThreadLocal context, {@link ThreadPerRequestSnippet#WHEN_IT_BREAKS}
 * carries real stack traces and easy debugging — in the {@code findUser → findOrder →
 * chargeCard} vocabulary the Performance Comparison tab reuses two tabs later.
 *
 * <p>The switch is a {@link SegmentedPicker} rather than {@link SnippetPicker}, which is
 * bound to the Threads 101 {@code Snippet} set; the "ask before discarding a live edit"
 * guard that picker bakes in is reproduced here with a local suppression flag around
 * {@link SegmentedPicker#setValue}, exactly as {@link Threads101Tab} does through its own
 * confirm bar.
 */
public final class ThreadPerRequestTab implements DemoTab {

    /** Output looks like {@code http-1 -> [req-42] findUser}. Group 1 is who is speaking. */
    private static final Pattern SPEAKER = Pattern.compile("^(\\S+)\\s+->\\s");

    private static final List<String> SPEAKER_STYLES =
            List.of("speaker-a", "speaker-b", "speaker-c");

    /** Long enough that typing doesn't trigger a compile per keystroke. */
    private static final Duration RECOMPILE_DELAY = Duration.millis(400);

    private final JavaCodeArea editor = new JavaCodeArea();
    private final StreamConsole console = new StreamConsole();
    private final ChildJvmRunner runner = new ChildJvmRunner();
    private final SourceCompiler compiler = new SourceCompiler();

    private final SegmentedPicker<ThreadPerRequestSnippet> picker = new SegmentedPicker<>(
            List.of(ThreadPerRequestSnippet.values()),
            ThreadPerRequestSnippet::label,
            ThreadPerRequestSnippet.values()[0]);
    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Label caption = new Label();
    private final Label dirtyBadge = new Label("edited");
    private final HBox confirmBar;
    private final VBox node;

    /**
     * Thread name to colour, assigned on first sight and kept until the console is cleared.
     * Stable assignment is the requirement: if a request were re-coloured each run, comparing
     * one run against the one above it would mean nothing.
     */
    private final Map<String, String> speakerStyles = new LinkedHashMap<>();

    private final PauseTransition recompile = new PauseTransition(RECOMPILE_DELAY);

    private ThreadPerRequestSnippet snippet = ThreadPerRequestSnippet.values()[0];
    private int runCount;

    /** Guards against a programmatic {@code picker.setValue} re-entering its own listener. */
    private boolean suppressPickerSync;

    public ThreadPerRequestTab() {
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

        // Compile in the background whenever the buffer settles, so Run is never the thing
        // waiting on javac.
        recompile.setOnFinished(e -> compiler.prepare(editor.getSource()));
        editor.sourceProperty().addListener((obs, was, is) -> recompile.playFromStart());

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
                "Run this code in a separate JVM  (⌘R)\nRun it more than once."));

        stopButton.getStyleClass().add("secondary-button");
        stopButton.setOnAction(e -> stopDemo());
        stopButton.setTooltip(new Tooltip("Kill the child JVM  (⌘.)"));

        Label note = new Label("Runs stack up in the console so you can compare them.");
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

    private void showConfirm(ThreadPerRequestSnippet requested) {
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

    private void switchTo(ThreadPerRequestSnippet target) {
        snippet = target;
        loadSnippet(target);
    }

    private void loadSnippet(ThreadPerRequestSnippet target) {
        editor.loadSource(target.source());
        caption.setText(target.caption());
        compiler.prepare(target.source());
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

        String source = editor.getSource();
        Optional<SourceCompiler.Compiled> compiled = compiler.ready(source);

        setRunning(true);
        ChildJvmRunner.Listener listener = new ChildJvmRunner.Listener() {
            @Override
            public void onLines(List<String> lines) {
                for (String line : lines) {
                    console.appendLine(line, styleFor(line));
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

        if (compiled.isPresent()) {
            runner.startCompiled(compiled.get().classDir(), compiled.get().mainClass(),
                    List.of(), listener);
        } else {
            // Not compiled yet, or the presenter's edit does not compile. The source
            // launcher is slower but it prints the real javac error, which is what you want
            // on screen when you have just broken the code in front of everyone.
            runner.start(source, List.of(), listener);
        }
    }

    /** Which colour this line gets, based on who printed it. */
    private String styleFor(String line) {
        Matcher matcher = SPEAKER.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1);
        String existing = speakerStyles.get(name);
        if (existing != null) {
            return existing;
        }
        String assigned = SPEAKER_STYLES.get(speakerStyles.size() % SPEAKER_STYLES.size());
        speakerStyles.put(name, assigned);
        return assigned;
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
        speakerStyles.clear();
    }

    @Override
    public void shutdown() {
        runner.stop();
        compiler.shutdown();
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
