package loomdemo;

/**
 * The three ways a Java server has been written to survive blocking I/O. Used only by the
 * Performance Comparison tab and the server it drives.
 *
 * <p>This is deliberately <em>not</em> a third {@link Mode}. {@code Mode} is intrinsically
 * binary — {@link Mode#other()}, {@code DemoSources.forMode}, {@code ModeToggle} and the
 * scoreboard's two chips all assume exactly two values — and the Thread Bomb tab has no
 * third program to run. Same call the codebase already made for {@code Snippet}.
 *
 * <p>{@link #PRESENT} keeps the {@code future} style class on purpose. The label is stage
 * vocabulary and the style class is internal identity, so every existing
 * {@code .stats-panel.future} rule keeps working untouched — the split already documented
 * on {@link Mode}.
 */
public enum Era {

    PAST("Take me to the past",
            "Platform threads, pool of 200",
            "past", "#FFAB40",
            "PAST  ·  platform threads, pool of 200"),

    WORKAROUND("Take me to the workaround",
            "Async callbacks — no thread ever waits",
            "workaround", "#7E57C2",
            "WORKAROUND  ·  async callbacks, " + asyncThreads() + " threads"),

    PRESENT("Take me to the present",
            "Virtual threads, since Java 21",
            "future", "#0097A7",
            "PRESENT  ·  virtual threads");

    /**
     * Event-loop threads for the async server: one per core, the reactive default.
     *
     * <p>Measured on an 8-core M2 at 2,000 concurrency: one per core clears 5,000 requests
     * with zero errors, and raising it does not help — past a point the extra threads just
     * contend. The number is on screen in the panel header, so it is stage vocabulary as
     * much as configuration and lives here rather than in the server.
     */
    public static int asyncThreads() {
        return Runtime.getRuntime().availableProcessors();
    }

    private final String label;
    private final String subtitle;
    private final String styleClass;
    private final String accent;
    private final String panelHeader;

    Era(String label, String subtitle, String styleClass, String accent, String panelHeader) {
        this.label = label;
        this.subtitle = subtitle;
        this.styleClass = styleClass;
        this.accent = accent;
        this.panelHeader = panelHeader;
    }

    public String label() {
        return label;
    }

    /**
     * Short form for places where the full "Take me to the…" is noise rather than voice:
     * the chart legend, which now carries three of them, and the stop warning.
     */
    public String shortLabel() {
        return name().toLowerCase(java.util.Locale.US);
    }

    public String subtitle() {
        return subtitle;
    }

    /** Style class carrying this era's colour: {@code past} / {@code workaround} / {@code future}. */
    public String styleClass() {
        return styleClass;
    }

    /**
     * The identity colour, for places CSS cannot reach — chart series are styled through
     * inline strokes because JavaFX assigns them rotating default-colour classes.
     */
    public String accent() {
        return accent;
    }

    public String panelHeader() {
        return panelHeader;
    }
}
