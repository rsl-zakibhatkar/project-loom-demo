package loomdemo;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Parent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Owns the two global visual switches: light/dark and presentation mode.
 *
 * <p>Both are expressed purely as style classes on the scene root ({@code dark},
 * {@code presentation}); app.css does the rest. Dark mode follows the macOS system
 * appearance — and keeps following it live — until the presenter toggles it by hand,
 * at which point their choice sticks for the session.
 */
public final class Theme {

    public static final String STAGE_DARK = "dark";
    public static final String STAGE_PRESENTATION = "presentation";

    private final BooleanProperty dark = new SimpleBooleanProperty(false);
    private final BooleanProperty presentation = new SimpleBooleanProperty(false);
    private boolean followSystem = true;

    public Theme(Parent root) {
        dark.set(detectSystemDark());
        applyClass(root, STAGE_DARK, dark.get());

        dark.addListener((obs, was, is) -> applyClass(root, STAGE_DARK, is));
        presentation.addListener((obs, was, is) -> applyClass(root, STAGE_PRESENTATION, is));

        // Live-follow the system appearance until the user takes manual control.
        try {
            Platform.getPreferences().colorSchemeProperty().addListener((obs, was, is) -> {
                if (followSystem && is != null) {
                    dark.set(is == ColorScheme.DARK);
                }
            });
        } catch (Throwable ignored) {
            // Preferences API unavailable; the initial detection above still applies.
        }
    }

    public BooleanProperty darkProperty() {
        return dark;
    }

    public BooleanProperty presentationProperty() {
        return presentation;
    }

    /** Manual toggle — stops tracking the system appearance for the rest of the session. */
    public void toggleDarkManually() {
        followSystem = false;
        dark.set(!dark.get());
    }

    public void togglePresentation() {
        presentation.set(!presentation.get());
    }

    private static void applyClass(Parent root, String styleClass, boolean on) {
        if (on) {
            if (!root.getStyleClass().contains(styleClass)) {
                root.getStyleClass().add(styleClass);
            }
        } else {
            root.getStyleClass().remove(styleClass);
        }
    }

    /**
     * JavaFX's Preferences API is authoritative when it answers; on the odd macOS/JavaFX
     * combination that reports no scheme we shell out to {@code defaults} instead. Both
     * paths are wrapped — a theme guess must never stop the app from starting.
     */
    private static boolean detectSystemDark() {
        try {
            ColorScheme scheme = Platform.getPreferences().getColorScheme();
            if (scheme == ColorScheme.DARK) {
                return true;
            }
            if (scheme == ColorScheme.LIGHT) {
                return false;
            }
        } catch (Throwable ignored) {
            // fall through to the defaults(1) probe
        }
        return detectSystemDarkViaDefaults();
    }

    private static boolean detectSystemDarkViaDefaults() {
        Process process = null;
        try {
            process = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.readLine();
            }
            process.waitFor(2, TimeUnit.SECONDS);
            return out != null && out.trim().equalsIgnoreCase("Dark");
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
