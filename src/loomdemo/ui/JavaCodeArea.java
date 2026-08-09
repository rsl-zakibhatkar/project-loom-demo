package loomdemo.ui;

import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An editable Java source view with regex syntax highlighting and line numbers.
 *
 * <p>Editable is the point: the presenter changes numbers on stage and re-runs. The
 * {@code dirty} flag tracks whether the buffer still matches the template that was
 * loaded into it, so switching modes can warn before throwing live edits away.
 */
public final class JavaCodeArea {

    private static final String[] KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "record", "return", "sealed", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "var", "void", "volatile", "while", "yield",
            "true", "false", "null"
    };

    // Comments and strings are matched first so keywords inside them stay unhighlighted.
    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\n]*|/\\*(?:.|\\R)*?\\*/)"
                    + "|(?<STRING>\"(?:[^\"\\\\]|\\\\.)*\")"
                    + "|(?<ANNOTATION>@\\w+)"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<TYPE>\\b[A-Z][A-Za-z0-9_]*\\b)"
                    + "|(?<NUMBER>\\b\\d[\\d_]*(?:\\.\\d+)?[LlDdFf]?\\b)");

    private static final double MIN_EM = 0.75;
    private static final double MAX_EM = 2.4;
    private static final double STEP_EM = 0.1;

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane;
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    private final PauseTransition highlightDebounce = new PauseTransition(Duration.millis(120));

    private String loadedSource = "";
    private double fontEm = 1.0;

    public JavaCodeArea() {
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("code-area");
        area.setWrapText(false);

        highlightDebounce.setOnFinished(e -> applyHighlighting());
        area.textProperty().addListener((obs, old, text) -> {
            dirty.set(!text.equals(loadedSource));
            highlightDebounce.playFromStart();
        });

        scrollPane = new VirtualizedScrollPane<>(area);
        applyFont();
    }

    /** Replace the buffer with a template and reset the dirty flag. */
    public void loadSource(String source) {
        loadedSource = source;
        area.replaceText(source);
        area.moveTo(0);
        area.showParagraphAtTop(0);
        dirty.set(false);
        applyHighlighting();
    }

    public String getSource() {
        return area.getText();
    }

    /**
     * The live buffer contents. Threads 101 watches this to precompile in the background
     * shortly after the presenter stops typing.
     */
    public ObservableValue<String> sourceProperty() {
        return area.textProperty();
    }

    /** True when the presenter has edited the buffer since the template was loaded. */
    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void adjustFont(double steps) {
        fontEm = Math.max(MIN_EM, Math.min(MAX_EM, fontEm + steps * STEP_EM));
        applyFont();
    }

    private void applyFont() {
        area.setStyle("-fx-font-size: " + String.format("%.3f", fontEm) + "em;");
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
    }

    public Region getNode() {
        return scrollPane;
    }

    private void applyHighlighting() {
        String text = area.getText();
        if (text.isEmpty()) {
            return;
        }
        try {
            area.setStyleSpans(0, computeHighlighting(text));
        } catch (Throwable ignored) {
            // Highlighting is cosmetic — never let it break editing mid-demo.
        }
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass = matchedStyleClass(matcher);
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(List.of(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private static String matchedStyleClass(Matcher matcher) {
        if (matcher.group("COMMENT") != null) {
            return "comment";
        }
        if (matcher.group("STRING") != null) {
            return "string";
        }
        if (matcher.group("ANNOTATION") != null) {
            return "annotation";
        }
        if (matcher.group("KEYWORD") != null) {
            return "keyword";
        }
        if (matcher.group("TYPE") != null) {
            return "type";
        }
        return "number";
    }
}
