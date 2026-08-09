package loomdemo;

/** The two sides of the talk. Every demo in the app is either one or the other. */
public enum Mode {
    PAST("Take me to the past", "Platform threads, bounded pools", "past", "#FFAB40"),
    FUTURE("Take me to the future", "Virtual threads", "future", "#0097A7");

    private final String label;
    private final String subtitle;
    private final String styleClass;
    private final String accent;

    Mode(String label, String subtitle, String styleClass, String accent) {
        this.label = label;
        this.subtitle = subtitle;
        this.styleClass = styleClass;
        this.accent = accent;
    }

    /**
     * The identity colour, for places CSS cannot reach — chart series are styled through
     * inline strokes because JavaFX assigns them rotating default-colour classes.
     */
    public String accent() {
        return accent;
    }

    public String label() {
        return label;
    }

    public String subtitle() {
        return subtitle;
    }

    /** Style class carrying this mode's colour, e.g. {@code .past} / {@code .future}. */
    public String styleClass() {
        return styleClass;
    }

    public Mode other() {
        return this == PAST ? FUTURE : PAST;
    }
}
