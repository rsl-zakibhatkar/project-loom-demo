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
import loomdemo.exec.Snippet;
import loomdemo.exec.SourceCompiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The opening demo: two threads, five lines each, and an order nobody chose.
 *
 * <p>Same shape as {@link ThreadBombTab} — editable source on the left, live output on the
 * right, executed in a child JVM — with three differences that all come from this being
 * the tab the presenter re-runs over and over:
 *
 * <ul>
 *   <li>The console is <em>not</em> cleared between runs. Each run is separated by a
 *       {@code ──── Run #N ────} rule so consecutive runs can be compared on screen, which
 *       is the entire point of a demo about non-deterministic ordering.
 *   <li>Lines are coloured by the thread that printed them, so the interleaving reads from
 *       the back of the room without anyone parsing text.
 *   <li>The source is compiled ahead of time by {@link SourceCompiler}, so Run starts a
 *       JVM instead of a compiler. That is the difference between roughly 250 ms and
 *       roughly 25 ms to the first line — between "watch it again" and an awkward pause.
 * </ul>
 */
public final class Threads101Tab implements DemoTab {

    /** Snippet output looks like {@code cook-1 -> 3}. Group 1 is who is speaking. */
    private static final Pattern SPEAKER = Pattern.compile("^(\\S+)\\s+->\\s");

    private static final List<String> SPEAKER_STYLES =
            List.of("speaker-a", "speaker-b", "speaker-c");

    /** Long enough that typing doesn't trigger a compile per keystroke. */
    private static final Duration RECOMPILE_DELAY = Duration.millis(400);

    private final JavaCodeArea editor = new JavaCodeArea();
    private final StreamConsole console = new StreamConsole();
    private final ChildJvmRunner runner = new ChildJvmRunner();
    private final SourceCompiler compiler = new SourceCompiler();

    private final SnippetPicker picker = new SnippetPicker();
    private final Button runButton = new Button("▶  Run");
    private final Button stopButton = new Button("Stop");
    private final Label caption = new Label();
    private final Label dirtyBadge = new Label("edited");
    private final HBox confirmBar;
    private final VBox node;

    /**
     * Thread name to colour, assigned on first sight and kept until the console is
     * cleared. Stable assignment is the requirement: if cook-1 were re-coloured each run,
     * comparing one run against the one above it would mean nothing.
     */
    private final Map<String, String> speakerStyles = new LinkedHashMap<>();

    private final PauseTransition recompile = new PauseTransition(RECOMPILE_DELAY);

    private Snippet snippet = Snippet.values()[0];
    private int runCount;

    public Threads101Tab() {
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

        // Compile in the background whenever the buffer settles, so Run is never the
        // thing waiting on javac.
        recompile.setOnFinished(e -> compiler.prepare(editor.getSource()));
        editor.sourceProperty().addListener((obs, was, is) -> recompile.playFromStart());

        loadSnippet(snippet);
        setRunning(false);
    }

    private FlowPane buildControls() {
        picker.setGuard(requested -> {
            if (editor.isDirty()) {
                showConfirm(requested);
                return false;
            }
            return true;
        });
        picker.snippetProperty().addListener((obs, was, is) -> switchTo(is));

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

        // FlowPane rather than HBox: at 130% presentation fonts these controls no longer
        // fit on one line, and wrapping beats truncating the snippet names to "...".
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

    private void showConfirm(Snippet requested) {
        Object[] parts = (Object[]) confirmBar.getUserData();
        Label message = (Label) parts[0];
        Button discard = (Button) parts[1];
        message.setText("You have edited this code. Switching to \"" + requested.label()
                + "\" will replace it.");
        discard.setOnAction(e -> {
            hideConfirm();
            picker.setSnippet(requested);   // bypasses the guard, fires the listener
        });
        confirmBar.setVisible(true);
        confirmBar.setManaged(true);
    }

    private void hideConfirm() {
        confirmBar.setVisible(false);
        confirmBar.setManaged(false);
    }

    private void switchTo(Snippet target) {
        snippet = target;
        loadSnippet(target);
    }

    private void loadSnippet(Snippet target) {
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
            // launcher is slower but it prints the real javac error, which is what you
            // want on screen when you have just broken the code in front of everyone.
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
