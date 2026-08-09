package loomdemo;

/** The two sides of the talk. Every demo in the app is either one or the other. */
public enum Mode {
    PAST("Take me to the past", "Platform threads, bounded pools", "past"),
    FUTURE("Take me to the future", "Virtual threads", "future");

    private final String label;
    private final String subtitle;
    private final String styleClass;

    Mode(String label, String subtitle, String styleClass) {
        this.label = label;
        this.subtitle = subtitle;
        this.styleClass = styleClass;
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
