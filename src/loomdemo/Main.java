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
import javafx.stage.Stage;
import loomdemo.ui.PerfCompareTab;
import loomdemo.ui.Scoreboard;
import loomdemo.ui.ThreadBombTab;

/**
 * Application shell: title bar, the persistent scoreboard, the two demo tabs and the
 * global keyboard shortcuts.
 *
 * <p>Startup is deliberately cheap — no child JVM, no HTTP server, no load generator is
 * created here. Those spin up on first use so the app is on screen fast when it is
 * launched in front of an audience.
 */
public class Main extends Application {

    private ThreadBombTab threadBombTab;
    private PerfCompareTab perfCompareTab;
    private TabPane tabPane;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        Theme theme = new Theme(root);
        Scoreboard scoreboard = new Scoreboard();

        threadBombTab = new ThreadBombTab(scoreboard);
        perfCompareTab = new PerfCompareTab();

        Tab bombTab = new Tab("Thread Bomb", threadBombTab.getNode());
        bombTab.setClosable(false);
        Tab perfTab = new Tab("Performance Comparison", perfCompareTab.getNode());
        perfTab.setClosable(false);

        tabPane = new TabPane(bombTab, perfTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        root.setTop(buildTopBar(theme, scoreboard));
        root.setCenter(tabPane);

        Scene scene = new Scene(root, 1500, 960);
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
        put(scene, KeyCode.R, () -> current().runDemo());
        put(scene, KeyCode.PERIOD, () -> current().stopDemo());
        put(scene, KeyCode.K, () -> current().clearOutput());
        put(scene, KeyCode.P, theme::togglePresentation);
        put(scene, KeyCode.D, theme::toggleDarkManually);
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

    private DemoTab current() {
        return tabPane.getSelectionModel().getSelectedIndex() == 0 ? threadBombTab : perfCompareTab;
    }

    private void shutdown() {
        try {
            threadBombTab.shutdown();
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            perfCompareTab.shutdown();
        } catch (Throwable ignored) {
            // best effort
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
