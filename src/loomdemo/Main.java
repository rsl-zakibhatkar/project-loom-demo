package loomdemo;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import loomdemo.ui.PerfCompareTab;
import loomdemo.ui.Scoreboard;
import loomdemo.ui.ThreadBombTab;
import loomdemo.ui.Threads101Tab;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Application shell: title bar, the persistent scoreboard, the two demo tabs and the
 * global keyboard shortcuts.
 *
 * <p>Startup is deliberately cheap — no child JVM, no HTTP server, no load generator is
 * created here. Those spin up on first use so the app is on screen fast when it is
 * launched in front of an audience.
 */
public class Main extends Application {

    private final List<DemoTab> tabs = new ArrayList<>();
    private TabPane tabPane;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        Theme theme = new Theme(root);
        Scoreboard scoreboard = new Scoreboard();

        Threads101Tab threads101Tab = new Threads101Tab();
        ThreadBombTab threadBombTab = new ThreadBombTab(scoreboard);
        PerfCompareTab perfCompareTab = new PerfCompareTab();

        // Order is the talk's order: what a thread is, then what platform threads cost,
        // then what that costs a server.
        tabPane = new TabPane(
                demoTab("Threads 101", threads101Tab.getNode(), threads101Tab),
                demoTab("Thread Bomb", threadBombTab.getNode(), threadBombTab),
                demoTab("Performance Comparison", perfCompareTab.getNode(), perfCompareTab));
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        root.setTop(buildTopBar(theme, scoreboard));
        root.setCenter(tabPane);

        // Use whatever the projector actually gives us — presentation mode is 30% larger
        // and the comparison tab needs the height.
        var visual = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1500, visual.getWidth() * 0.94);
        double height = Math.min(1040, visual.getHeight() * 0.94);

        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(Main.class.getResource("app.css").toExternalForm());
        installShortcuts(scene, theme);

        stage.setTitle("Project Loom — Live Demo");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    private HBox buildTopBar(Theme theme, Scoreboard scoreboard) {
        Label title = new Label("Project Loom");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("virtual threads, live");
        subtitle.getStyleClass().add("app-subtitle");

        VBox titleBlock = new VBox(title, subtitle);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        ToggleButton presentationToggle = new ToggleButton("Presentation");
        presentationToggle.getStyleClass().add("secondary-button");
        presentationToggle.selectedProperty().bindBidirectional(theme.presentationProperty());
        presentationToggle.setTooltip(new Tooltip(
                "Bump every font size by 30% for the back of the room.  (⌘P)"));

        ToggleButton themeToggle = new ToggleButton();
        themeToggle.getStyleClass().add("secondary-button");
        themeToggle.setSelected(theme.darkProperty().get());
        themeToggle.setText(theme.darkProperty().get() ? "☀ Light" : "☽ Dark");
        theme.darkProperty().addListener((obs, was, isDark) -> {
            themeToggle.setSelected(isDark);
            themeToggle.setText(isDark ? "☀ Light" : "☽ Dark");
        });
        // Route through the Theme so it knows the presenter has taken manual control.
        themeToggle.setOnAction(e -> theme.toggleDarkManually());
        themeToggle.setTooltip(new Tooltip(
                "Follows your macOS appearance until you click this.  (⌘D)\n"
                        + "Light theme projects better on cheap projectors."));

        HBox bar = new HBox(titleBlock, spacer(), scoreboard.getNode(), spacer(),
                presentationToggle, themeToggle);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void installShortcuts(Scene scene, Theme theme) {
        put(scene, KeyCode.R, () -> onCurrent(DemoTab::runDemo));
        put(scene, KeyCode.PERIOD, () -> onCurrent(DemoTab::stopDemo));
        put(scene, KeyCode.K, () -> onCurrent(DemoTab::clearOutput));
        put(scene, KeyCode.P, theme::togglePresentation);
        put(scene, KeyCode.D, theme::toggleDarkManually);
    }

    private void onCurrent(Consumer<DemoTab> action) {
        DemoTab tab = current();
        if (tab != null) {
            action.accept(tab);
        }
    }

    private void put(Scene scene, KeyCode code, Runnable action) {
        scene.getAccelerators().put(
                new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    // A shortcut must never take the window down mid-talk.
                    try {
                        action.run();
                    } catch (Throwable t) {
                        System.err.println("Shortcut failed: " + t);
                    }
                });
    }

    /**
     * Build a tab and record which demo it drives.
     *
     * <p>The shortcuts used to find the demo by tab index, which silently sent ⌘R to the
     * wrong tab the moment a third one existed. Carrying the demo on the tab itself makes
     * that impossible to get wrong again.
     */
    private Tab demoTab(String title, javafx.scene.Node content, DemoTab demo) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        tab.setUserData(demo);
        tabs.add(demo);
        return tab;
    }

    private DemoTab current() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return selected == null ? null : (DemoTab) selected.getUserData();
    }

    private void shutdown() {
        for (DemoTab tab : tabs) {
            try {
                tab.shutdown();
            } catch (Throwable ignored) {
                // best effort — one tab failing to tear down must not block the others
            }
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
